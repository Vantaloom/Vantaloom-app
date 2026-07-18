# Vantaloom Go runner

This is a small, pure-Go Yaegi host. It is an interpreter, not the full Go
compiler/toolchain.

```text
libvantaloom_go.so --version
libvantaloom_go.so run path/to/main.go [args...]
```

The host uses Yaegi's restricted mode and does not register `unsafe`, `syscall`
or `os/exec`. It also removes process-spawn symbols and all standard-library
listener/server constructors, including `net.Listen`, `net.ListenConfig`,
`http.ListenAndServe`, `http.Server`, FCGI, CGI and httptest server packages.

Yaegi is not a security sandbox, and native standard-library values can expose
capabilities indirectly through reflection. Therefore the generated engine
manifest intentionally sets `loopbackEnforced: false`, `serverAllowed: false`
and `longRunningProcesses: false`. The backend must not use this runner for an
HTTP development server. Python and Node provide the loopback-enforced server
paths for this iteration.
