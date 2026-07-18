package main

import (
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/traefik/yaegi/interp"
)

const (
	runnerVersion = "0.1.0"
	yaegiVersion  = "0.16.1"
)

var buildVersion = "dev"

func main() {
	os.Exit(runCLI(os.Args[1:], os.Stdin, os.Stdout, os.Stderr))
}

func runCLI(arguments []string, stdin io.Reader, stdout, stderr io.Writer) int {
	if len(arguments) == 1 && (arguments[0] == "--version" || arguments[0] == "version") {
		fmt.Fprintf(
			stdout,
			"vantaloom-go %s (%s, yaegi v%s)\n",
			runnerVersion,
			buildVersion,
			yaegiVersion,
		)
		return 0
	}
	if len(arguments) < 2 || arguments[0] != "run" {
		fmt.Fprintln(stderr, "usage: vantaloom-go --version | run <file.go> [args...]")
		return 2
	}

	scriptPath, err := filepath.Abs(arguments[1])
	if err != nil {
		fmt.Fprintf(stderr, "resolve script path: %v\n", err)
		return 1
	}
	info, err := os.Stat(scriptPath)
	if err != nil {
		fmt.Fprintf(stderr, "open script: %v\n", err)
		return 1
	}
	if !info.Mode().IsRegular() || filepath.Ext(scriptPath) != ".go" {
		fmt.Fprintln(stderr, "run requires a regular .go file")
		return 2
	}

	interpreter := interp.New(interp.Options{
		Args:         append([]string{scriptPath}, arguments[2:]...),
		Env:          currentEnvironment(),
		GoPath:       "",
		Stdin:        stdin,
		Stdout:       stdout,
		Stderr:       stderr,
		Unrestricted: false,
	})
	if err := interpreter.Use(restrictedSymbols()); err != nil {
		fmt.Fprintf(stderr, "initialize Go runtime: %v\n", err)
		return 1
	}
	if _, err := interpreter.EvalPath(scriptPath); err != nil {
		fmt.Fprintf(stderr, "run Go source: %v\n", err)
		if panicError, ok := err.(interp.Panic); ok {
			fmt.Fprintln(stderr, string(panicError.Stack))
		}
		return 1
	}
	return 0
}
