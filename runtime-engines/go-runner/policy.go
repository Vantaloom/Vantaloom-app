package main

import (
	"os"
	"reflect"
	"strings"

	"github.com/traefik/yaegi/interp"
	"github.com/traefik/yaegi/stdlib"
)

var deniedPackages = map[string]struct{}{
	"github.com/traefik/yaegi/stdlib/stdlib": {},
	"net/http/cgi/cgi":                       {},
	"net/http/fcgi/fcgi":                     {},
	"net/http/httptest/httptest":             {},
	"os/exec/exec":                           {},
	"syscall/syscall":                        {},
	"unsafe/unsafe":                          {},
}

var deniedSymbols = map[string][]string{
	"net/net": {
		"FileListener",
		"FilePacketConn",
		"Listen",
		"ListenConfig",
		"ListenIP",
		"ListenMulticastUDP",
		"ListenPacket",
		"ListenTCP",
		"ListenUDP",
		"ListenUnix",
		"ListenUnixgram",
	},
	"net/http/http": {
		"ListenAndServe",
		"ListenAndServeTLS",
		"Serve",
		"ServeTLS",
		"Server",
	},
	"os/os": {
		"Exit",
		"FindProcess",
		"StartProcess",
	},
}

func restrictedSymbols() interp.Exports {
	exports := make(interp.Exports, len(stdlib.Symbols))
	for packagePath, packageSymbols := range stdlib.Symbols {
		if _, denied := deniedPackages[packagePath]; denied {
			continue
		}
		clonedSymbols := make(map[string]reflect.Value, len(packageSymbols))
		for name, value := range packageSymbols {
			clonedSymbols[name] = value
		}
		exports[packagePath] = clonedSymbols
	}
	for packagePath, names := range deniedSymbols {
		packageSymbols := exports[packagePath]
		for _, name := range names {
			delete(packageSymbols, name)
		}
	}
	return exports
}

func restrictedEnvironment(environment []string) []string {
	allowed := map[string]struct{}{
		"HOME":                       {},
		"HTTP_PROXY":                 {},
		"HTTPS_PROXY":                {},
		"LANG":                       {},
		"LC_ALL":                     {},
		"NO_PROXY":                   {},
		"TMPDIR":                     {},
		"TZ":                         {},
		"VANTALOOM_PROJECT_ROOT":     {},
		"VANTALOOM_RUNTIME_DATA_DIR": {},
	}
	filtered := make([]string, 0, len(environment))
	for _, entry := range environment {
		name, _, found := strings.Cut(entry, "=")
		if !found {
			name = entry
		}
		if _, ok := allowed[strings.ToUpper(name)]; ok {
			filtered = append(filtered, entry)
		}
	}
	return filtered
}

func currentEnvironment() []string {
	return restrictedEnvironment(os.Environ())
}
