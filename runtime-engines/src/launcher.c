#include <Python.h>

#include <arpa/inet.h>
#include <errno.h>
#include <limits.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "python_extensions.generated.h"

#define APP_FILES_DIR_ENV "VANTALOOM_APP_FILES_DIR"
#define BUNDLE_DIR_ENV "VANTALOOM_RUNTIME_BUNDLE_DIR"
#define STATE_DIR_ENV "VANTALOOM_RUNTIME_DATA_DIR"
#define LAUNCHER_NAME "libvantaloom_python.so"
#define NODE_NAME "libvantaloom_node.so"
#define PYTHON_VERSION "3.14"


typedef struct {
    char native_dir[PATH_MAX];
    char app_files_dir[PATH_MAX];
    char bundle_dir[PATH_MAX];
    char state_dir[PATH_MAX];
    char launcher[PATH_MAX];
    char node[PATH_MAX];
} RuntimePaths;


typedef struct {
    char read_bundle[PATH_MAX + 32];
    char read_state[PATH_MAX + 32];
    char write_state[PATH_MAX + 32];
    char read_cwd[PATH_MAX + 32];
    char write_cwd[PATH_MAX + 32];
    const char *arguments[6];
    size_t count;
} NodePolicy;


static int path_join(char *output, size_t output_size,
                     const char *left, const char *right) {
    int count = snprintf(output, output_size, "%s/%s", left, right);
    if (count < 0 || (size_t)count >= output_size) {
        fprintf(stderr, "runtime path is too long: %s/%s\n", left, right);
        return -1;
    }
    return 0;
}


static const char *base_name(const char *path) {
    const char *slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
}


static int ensure_directory(const char *path) {
    struct stat status;
    if (mkdir(path, 0700) == 0) {
        return 0;
    }
    if (errno != EEXIST) {
        fprintf(stderr, "mkdir %s: %s\n", path, strerror(errno));
        return -1;
    }
    if (stat(path, &status) != 0 || !S_ISDIR(status.st_mode)) {
        fprintf(stderr, "runtime path is not a directory: %s\n", path);
        return -1;
    }
    return 0;
}


static int ensure_directory_tree(const char *path) {
    char current[PATH_MAX];
    size_t length = strlen(path);
    if (length == 0 || length >= sizeof(current) || path[0] != '/') {
        fprintf(stderr, "runtime directory must be absolute: %s\n", path);
        return -1;
    }
    memcpy(current, path, length + 1);
    for (char *cursor = current + 1; *cursor; cursor++) {
        if (*cursor != '/') {
            continue;
        }
        *cursor = '\0';
        if (ensure_directory(current) != 0) {
            return -1;
        }
        *cursor = '/';
    }
    return ensure_directory(current);
}


static int derive_native_dir(char *output, size_t output_size) {
    ssize_t length = readlink("/proc/self/exe", output, output_size - 1);
    if (length < 0) {
        fprintf(stderr, "readlink /proc/self/exe: %s\n", strerror(errno));
        return -1;
    }
    if ((size_t)length >= output_size - 1) {
        fprintf(stderr, "native library path exceeds PATH_MAX\n");
        return -1;
    }
    output[length] = '\0';
    char *slash = strrchr(output, '/');
    if (slash == NULL || slash == output) {
        fprintf(stderr, "unexpected executable path: %s\n", output);
        return -1;
    }
    *slash = '\0';
    return 0;
}


static int path_is_within(const char *root, const char *candidate) {
    size_t root_length = strlen(root);
    if (root_length == 1 && root[0] == '/') {
        return candidate[0] == '/';
    }
    return strncmp(root, candidate, root_length) == 0 &&
        (candidate[root_length] == '\0' || candidate[root_length] == '/');
}


static int path_is_strictly_within(const char *root, const char *candidate) {
    return strcmp(root, candidate) != 0 && path_is_within(root, candidate);
}


static int paths_overlap(const char *left, const char *right) {
    return path_is_within(left, right) || path_is_within(right, left);
}


static int resolve_directory_environment(const char *name, int create,
                                         char *output, size_t output_size) {
    const char *configured = getenv(name);
    if (configured == NULL || configured[0] != '/') {
        fprintf(stderr, "%s must name an absolute directory\n", name);
        return -1;
    }
    if (create && ensure_directory_tree(configured) != 0) {
        return -1;
    }
    char *resolved = realpath(configured, NULL);
    if (resolved == NULL) {
        fprintf(stderr, "realpath %s: %s\n", configured, strerror(errno));
        return -1;
    }
    size_t resolved_length = strlen(resolved);
    if (resolved_length >= output_size) {
        fprintf(stderr, "%s exceeds PATH_MAX\n", name);
        free(resolved);
        return -1;
    }
    memcpy(output, resolved, resolved_length + 1);
    free(resolved);
    struct stat status;
    if (stat(output, &status) != 0 || !S_ISDIR(status.st_mode)) {
        fprintf(stderr, "%s is not a directory: %s\n", name, output);
        return -1;
    }
    return 0;
}


static int initialize_paths(RuntimePaths *paths) {
    if (derive_native_dir(paths->native_dir, sizeof(paths->native_dir)) != 0) {
        return -1;
    }
    if (resolve_directory_environment(
            APP_FILES_DIR_ENV, 0, paths->app_files_dir,
            sizeof(paths->app_files_dir)) != 0 ||
        resolve_directory_environment(
            BUNDLE_DIR_ENV, 0, paths->bundle_dir,
            sizeof(paths->bundle_dir)) != 0 ||
        resolve_directory_environment(
            STATE_DIR_ENV, 1, paths->state_dir,
            sizeof(paths->state_dir)) != 0) {
        return -1;
    }
    if (!path_is_strictly_within(paths->app_files_dir, paths->bundle_dir) ||
        !path_is_strictly_within(paths->app_files_dir, paths->state_dir)) {
        fprintf(stderr,
                "runtime bundle and state must be beneath app files\n");
        return -1;
    }
    if (paths_overlap(paths->bundle_dir, paths->state_dir)) {
        fprintf(stderr, "runtime bundle and state must not overlap\n");
        return -1;
    }
    if (path_join(paths->launcher, sizeof(paths->launcher),
                  paths->native_dir, LAUNCHER_NAME) != 0 ||
        path_join(paths->node, sizeof(paths->node),
                  paths->native_dir, NODE_NAME) != 0) {
        return -1;
    }
    return 0;
}


static int set_default_environment(const char *name, const char *value) {
    if (getenv(name) != NULL) {
        return 0;
    }
    if (setenv(name, value, 0) != 0) {
        fprintf(stderr, "setenv %s: %s\n", name, strerror(errno));
        return -1;
    }
    return 0;
}


static int set_managed_environment(const char *name, const char *value) {
    if (setenv(name, value, 1) != 0) {
        fprintf(stderr, "setenv %s: %s\n", name, strerror(errno));
        return -1;
    }
    return 0;
}


static int set_managed_subdirectory(const RuntimePaths *paths,
                                    const char *name,
                                    const char *relative) {
    char value[PATH_MAX];
    if (path_join(value, sizeof(value), paths->state_dir, relative) != 0 ||
        ensure_directory_tree(value) != 0) {
        return -1;
    }
    return set_managed_environment(name, value);
}


static int ensure_symlink(const char *link_path, const char *target_path) {
    struct stat target_status;
    if (stat(target_path, &target_status) != 0 ||
        !S_ISREG(target_status.st_mode)) {
        fprintf(stderr, "immutable runtime target is missing: %s\n", target_path);
        return -1;
    }

    struct stat link_status;
    if (lstat(link_path, &link_status) == 0) {
        if (!S_ISLNK(link_status.st_mode)) {
            fprintf(stderr, "refusing to replace non-symlink runtime path: %s\n",
                    link_path);
            return -1;
        }
        char current_target[PATH_MAX];
        ssize_t length = readlink(link_path, current_target,
                                  sizeof(current_target) - 1);
        if (length < 0) {
            fprintf(stderr, "readlink %s: %s\n", link_path, strerror(errno));
            return -1;
        }
        current_target[length] = '\0';
        if (strcmp(current_target, target_path) == 0) {
            return 0;
        }
        if (unlink(link_path) != 0) {
            fprintf(stderr, "unlink stale runtime symlink %s: %s\n",
                    link_path, strerror(errno));
            return -1;
        }
    } else if (errno != ENOENT) {
        fprintf(stderr, "lstat %s: %s\n", link_path, strerror(errno));
        return -1;
    }

    if (symlink(target_path, link_path) != 0) {
        fprintf(stderr, "symlink %s -> %s: %s\n", link_path, target_path,
                strerror(errno));
        return -1;
    }
    return 0;
}


static int prepare_command_links(const RuntimePaths *paths) {
    char bin_dir[PATH_MAX];
    if (path_join(bin_dir, sizeof(bin_dir), paths->state_dir, "bin") != 0 ||
        ensure_directory_tree(bin_dir) != 0) {
        return -1;
    }
    const char *launcher_commands[] = {
        "python", "python3", "pip", "pip3", "node", "npm", "npx",
    };
    for (size_t index = 0;
         index < sizeof(launcher_commands) / sizeof(launcher_commands[0]);
         index++) {
        char link_path[PATH_MAX];
        if (path_join(link_path, sizeof(link_path), bin_dir,
                      launcher_commands[index]) != 0 ||
            ensure_symlink(link_path, paths->launcher) != 0) {
            return -1;
        }
    }
    const char *old_path = getenv("PATH");
    if (old_path == NULL || old_path[0] == '\0') {
        old_path = "/system/bin:/system/xbin";
    }
    size_t required = strlen(bin_dir) + strlen(old_path) + 2;
    char *new_path = malloc(required);
    if (new_path == NULL) {
        fprintf(stderr, "unable to allocate PATH\n");
        return -1;
    }
    snprintf(new_path, required, "%s:%s", bin_dir, old_path);
    int result = setenv("PATH", new_path, 1);
    free(new_path);
    if (result != 0) {
        fprintf(stderr, "setenv PATH: %s\n", strerror(errno));
        return -1;
    }
    return 0;
}


static int prepare_environment(const RuntimePaths *paths) {
    if (unsetenv("NODE_OPTIONS") != 0) {
        fprintf(stderr, "unsetenv NODE_OPTIONS: %s\n", strerror(errno));
        return -1;
    }
    if (set_managed_subdirectory(paths, "HOME", "home") != 0 ||
        set_managed_subdirectory(paths, "TMPDIR", "tmp") != 0 ||
        set_managed_subdirectory(paths, "XDG_CACHE_HOME", "cache") != 0 ||
        set_managed_subdirectory(paths, "XDG_CONFIG_HOME", "config") != 0 ||
        set_managed_subdirectory(paths, "XDG_DATA_HOME", "share") != 0 ||
        set_managed_subdirectory(paths, "PYTHONUSERBASE", "python-user") != 0 ||
        set_managed_subdirectory(
            paths, "PYTHONPYCACHEPREFIX", "cache/python/pycache") != 0 ||
        set_managed_subdirectory(paths, "PIP_CACHE_DIR", "cache/pip") != 0 ||
        set_managed_subdirectory(paths, "NPM_CONFIG_CACHE", "cache/npm") != 0 ||
        set_managed_subdirectory(paths, "NPM_CONFIG_PREFIX", "node-prefix") != 0) {
        return -1;
    }

    char history[PATH_MAX];
    if (path_join(history, sizeof(history), paths->state_dir,
                  "home/.node_repl_history") != 0 ||
        set_managed_environment(
            "VANTALOOM_NODE_LAUNCHER", paths->launcher) != 0 ||
        set_managed_environment("NODE_REPL_HISTORY", history) != 0 ||
        set_managed_environment("PIP_USER", "1") != 0 ||
        unsetenv("npm_config_ignore_scripts") != 0 ||
        set_managed_environment("NPM_CONFIG_IGNORE_SCRIPTS", "true") != 0 ||
        set_managed_environment("SHELL", "/system/bin/sh") != 0 ||
        set_managed_environment(
            "NPM_CONFIG_SCRIPT_SHELL", "/system/bin/sh") != 0 ||
        set_default_environment("SSL_CERT_DIR", "/system/etc/security/cacerts") != 0 ||
        set_default_environment("PYTHONUTF8", "1") != 0) {
        return -1;
    }
    return prepare_command_links(paths);
}


static int prepare_python_extensions(const RuntimePaths *paths) {
    char extension_dir[PATH_MAX];
    if (path_join(extension_dir, sizeof(extension_dir), paths->state_dir,
                  "python-lib-dynload") != 0 ||
        ensure_directory_tree(extension_dir) != 0) {
        return -1;
    }
    for (size_t index = 0; index < VANTALOOM_PYTHON_EXTENSION_COUNT; index++) {
        const char *original_name = VANTALOOM_PYTHON_EXTENSIONS[index][0];
        const char *native_name = VANTALOOM_PYTHON_EXTENSIONS[index][1];
        if (strchr(original_name, '/') != NULL ||
            strncmp(native_name, "libvantaloom_pyext_", 20) != 0 ||
            strchr(native_name, '/') != NULL) {
            fprintf(stderr, "invalid compiled Python extension mapping\n");
            return -1;
        }
        char link_path[PATH_MAX];
        char target_path[PATH_MAX];
        if (path_join(link_path, sizeof(link_path), extension_dir,
                      original_name) != 0 ||
            path_join(target_path, sizeof(target_path), paths->native_dir,
                      native_name) != 0 ||
            ensure_symlink(link_path, target_path) != 0) {
            return -1;
        }
    }
    if (setenv("PYTHONPATH", extension_dir, 1) != 0) {
        fprintf(stderr, "setenv PYTHONPATH: %s\n", strerror(errno));
        return -1;
    }
    return 0;
}


static int is_loopback_host(const char *host) {
    struct in_addr ipv4;
    if (inet_pton(AF_INET, host, &ipv4) == 1) {
        const unsigned char *bytes =
            (const unsigned char *)&ipv4.s_addr;
        return bytes[0] == 127;
    }
    struct in6_addr ipv6;
    if (inet_pton(AF_INET6, host, &ipv6) == 1) {
        const unsigned char *bytes =
            (const unsigned char *)&ipv6.s6_addr;
        for (size_t index = 0; index < 15; index++) {
            if (bytes[index] != 0) {
                return 0;
            }
        }
        return bytes[15] == 1;
    }
    return 0;
}


static int python_socket_audit_hook(const char *event, PyObject *args,
                                    void *user_data) {
    (void)user_data;
    static const char *const denied_events[] = {
        "ctypes.call_function",
        "ctypes.dlopen",
        "ctypes.dlsym",
        "os.exec",
        "os.fork",
        "os.forkpty",
        "os.posix_spawn",
        "os.spawn",
        "os.system",
        "subprocess.Popen",
    };
    for (size_t index = 0;
         index < sizeof(denied_events) / sizeof(denied_events[0]);
         index++) {
        if (strcmp(event, denied_events[index]) == 0) {
            PyErr_Format(PyExc_PermissionError,
                         "%s is disabled in the Vantaloom mobile Python runtime",
                         event);
            return -1;
        }
    }
    if (strcmp(event, "socket.bind") != 0) {
        return 0;
    }
    if (!PyTuple_Check(args) || PyTuple_GET_SIZE(args) < 2) {
        PyErr_SetString(PyExc_PermissionError,
                        "invalid socket.bind audit arguments");
        return -1;
    }
    PyObject *address = PyTuple_GET_ITEM(args, 1);
    if (!PyTuple_Check(address) || PyTuple_GET_SIZE(address) < 2) {
        return 0;
    }
    PyObject *host_object = PyTuple_GET_ITEM(address, 0);
    const char *host = NULL;
    if (PyUnicode_Check(host_object)) {
        host = PyUnicode_AsUTF8(host_object);
        if (host == NULL) {
            return -1;
        }
    } else if (PyBytes_Check(host_object)) {
        Py_ssize_t host_length = PyBytes_GET_SIZE(host_object);
        host = PyBytes_AS_STRING(host_object);
        if ((Py_ssize_t)strlen(host) != host_length) {
            PyErr_SetString(PyExc_PermissionError,
                            "socket.bind address contains NUL bytes");
            return -1;
        }
    } else {
        PyErr_SetString(PyExc_PermissionError,
                        "socket.bind requires a numeric loopback address");
        return -1;
    }
    if (!is_loopback_host(host)) {
        PyErr_Format(PyExc_PermissionError,
                     "Vantaloom mobile runtimes may only bind loopback; got %s",
                     host);
        return -1;
    }
    return 0;
}


static int python_status_code(PyStatus status) {
    if (PyStatus_IsExit(status)) {
        return status.exitcode;
    }
    fprintf(stderr, "Python initialization failed: %s\n",
            status.err_msg ? status.err_msg : "unknown error");
    return 1;
}


static int run_python(const RuntimePaths *paths, int argc, char **argv) {
    char python_home[PATH_MAX];
    char encodings_path[PATH_MAX];
    char python_executable[PATH_MAX];
    if (path_join(python_home, sizeof(python_home), paths->bundle_dir,
                  "python") != 0 ||
        path_join(encodings_path, sizeof(encodings_path), python_home,
                  "lib/python" PYTHON_VERSION "/encodings/__init__.py") != 0 ||
        path_join(python_executable, sizeof(python_executable), paths->state_dir,
                  "bin/python") != 0) {
        return 1;
    }
    if (access(encodings_path, R_OK) != 0) {
        fprintf(stderr, "Python assets are not installed at %s\n", python_home);
        return 1;
    }
    if (prepare_python_extensions(paths) != 0 ||
        setenv("PYTHONHOME", python_home, 1) != 0) {
        return 1;
    }
    if (PySys_AddAuditHook(python_socket_audit_hook, NULL) != 0) {
        fprintf(stderr, "unable to install Python loopback audit hook\n");
        return 1;
    }

    PyConfig config;
    PyConfig_InitPythonConfig(&config);
    config.user_site_directory = 1;

    PyStatus status = PyConfig_SetBytesArgv(&config, argc, argv);
    if (!PyStatus_Exception(status)) {
        status = PyConfig_SetBytesString(&config, &config.home, python_home);
    }
    if (!PyStatus_Exception(status)) {
        status = PyConfig_SetBytesString(
            &config, &config.executable, python_executable);
    }
    if (!PyStatus_Exception(status)) {
        status = PyConfig_SetBytesString(
            &config, &config.base_executable, python_executable);
    }
    if (!PyStatus_Exception(status)) {
        status = Py_InitializeFromConfig(&config);
    }
    if (PyStatus_Exception(status)) {
        int code = python_status_code(status);
        PyConfig_Clear(&config);
        return code;
    }
    PyConfig_Clear(&config);
    return Py_RunMain();
}


static char **allocate_arguments(size_t count) {
    if (count > (SIZE_MAX / sizeof(char *)) - 1) {
        return NULL;
    }
    return calloc(count + 1, sizeof(char *));
}


static int format_node_permission(char *output, size_t output_size,
                                  const char *permission, const char *path) {
    int count = snprintf(output, output_size, "%s=%s", permission, path);
    if (count < 0 || (size_t)count >= output_size) {
        fprintf(stderr, "Node.js permission path exceeds PATH_MAX\n");
        return -1;
    }
    return 0;
}


static int initialize_node_policy(const RuntimePaths *paths,
                                  NodePolicy *policy) {
    char cwd[PATH_MAX];
    if (getcwd(cwd, sizeof(cwd)) == NULL) {
        fprintf(stderr, "getcwd: %s\n", strerror(errno));
        return -1;
    }
    if (!path_is_strictly_within(paths->app_files_dir, cwd)) {
        fprintf(stderr,
                "Node.js working directory must be beneath app files: %s\n",
                cwd);
        return -1;
    }
    if (paths_overlap(cwd, paths->bundle_dir)) {
        fprintf(stderr,
                "Node.js working directory must not overlap runtime bundle: %s\n",
                cwd);
        return -1;
    }
    if (format_node_permission(
            policy->read_bundle, sizeof(policy->read_bundle),
            "--allow-fs-read", paths->bundle_dir) != 0 ||
        format_node_permission(
            policy->read_state, sizeof(policy->read_state),
            "--allow-fs-read", paths->state_dir) != 0 ||
        format_node_permission(
            policy->write_state, sizeof(policy->write_state),
            "--allow-fs-write", paths->state_dir) != 0 ||
        format_node_permission(
            policy->read_cwd, sizeof(policy->read_cwd),
            "--allow-fs-read", cwd) != 0 ||
        format_node_permission(
            policy->write_cwd, sizeof(policy->write_cwd),
            "--allow-fs-write", cwd) != 0) {
        return -1;
    }
    policy->arguments[0] = "--permission";
    policy->arguments[1] = policy->read_bundle;
    policy->arguments[2] = policy->read_state;
    policy->arguments[3] = policy->write_state;
    policy->arguments[4] = policy->read_cwd;
    policy->arguments[5] = policy->write_cwd;
    policy->count = 6;
    return 0;
}


static int forbidden_node_argument(const char *argument) {
    static const char *const forbidden[] = {
        "--allow-addons",
        "--allow-child-process",
        "--allow-fs-read",
        "--allow-fs-write",
        "--allow-wasi",
        "--allow-worker",
        "--env-file",
        "--env-file-if-exists",
        "--experimental-config-file",
        "--experimental-default-config-file",
        "--experimental-permission",
        "--inspect",
        "--inspect-brk",
        "--inspect-port",
        "--inspect-wait",
        "--no-experimental-permission",
        "--no-permission",
        "--permission",
    };
    for (size_t index = 0; index < sizeof(forbidden) / sizeof(forbidden[0]);
         index++) {
        size_t length = strlen(forbidden[index]);
        if (strncmp(argument, forbidden[index], length) == 0 &&
            (argument[length] == '\0' || argument[length] == '=')) {
            return 1;
        }
    }
    return 0;
}


static int validate_node_arguments(int count, char **arguments) {
    for (int index = 0; index < count; index++) {
        if (forbidden_node_argument(arguments[index])) {
            fprintf(stderr, "Node.js option is forbidden by mobile policy: %s\n",
                    arguments[index]);
            return -1;
        }
    }
    return 0;
}


static int validate_npm_arguments(int count, char **arguments) {
    for (int index = 0; index < count; index++) {
        const char *argument = arguments[index];
        if (strcasecmp(argument, "--no-ignore-scripts") == 0 ||
            strcasecmp(argument, "--ignore-scripts=false") == 0 ||
            strcasecmp(argument, "--ignore-scripts=0") == 0) {
            fprintf(stderr,
                    "npm lifecycle scripts are permanently disabled: %s\n",
                    argument);
            return -1;
        }
        if (strcasecmp(argument, "--ignore-scripts") == 0 &&
            index + 1 < count &&
            (strcasecmp(arguments[index + 1], "false") == 0 ||
             strcmp(arguments[index + 1], "0") == 0)) {
            fprintf(stderr,
                    "npm lifecycle scripts are permanently disabled\n");
            return -1;
        }
    }
    return 0;
}


static int run_python_module(const RuntimePaths *paths, const char *applet,
                             const char *module, int argument_count,
                             char **arguments) {
    size_t count = (size_t)argument_count + 3;
    char **python_arguments = allocate_arguments(count);
    if (python_arguments == NULL) {
        fprintf(stderr, "unable to allocate Python arguments\n");
        return 1;
    }
    python_arguments[0] = (char *)applet;
    python_arguments[1] = "-m";
    python_arguments[2] = (char *)module;
    for (int index = 0; index < argument_count; index++) {
        python_arguments[index + 3] = arguments[index];
    }
    int result = run_python(paths, (int)count, python_arguments);
    free(python_arguments);
    return result;
}


static int exec_node(const RuntimePaths *paths, int argument_count,
                     char **arguments) {
    if (validate_node_arguments(argument_count - 1, arguments + 1) != 0) {
        return 2;
    }
    NodePolicy policy;
    if (initialize_node_policy(paths, &policy) != 0) {
        return 1;
    }
    size_t count = (size_t)argument_count + policy.count;
    char **node_arguments = allocate_arguments(count);
    if (node_arguments == NULL) {
        fprintf(stderr, "unable to allocate Node.js arguments\n");
        return 1;
    }
    node_arguments[0] = (char *)paths->node;
    for (size_t index = 0; index < policy.count; index++) {
        node_arguments[index + 1] = (char *)policy.arguments[index];
    }
    for (int index = 1; index < argument_count; index++) {
        node_arguments[index + policy.count] = arguments[index];
    }
    execv(paths->node, node_arguments);
    fprintf(stderr, "execv %s: %s\n", paths->node, strerror(errno));
    free(node_arguments);
    return 127;
}


static int exec_node_module(const RuntimePaths *paths, const char *relative_cli,
                            int argument_count, char **arguments) {
    if (validate_node_arguments(argument_count, arguments) != 0 ||
        validate_npm_arguments(argument_count, arguments) != 0) {
        return 2;
    }
    char cli[PATH_MAX];
    if (path_join(cli, sizeof(cli), paths->bundle_dir, relative_cli) != 0) {
        return 1;
    }
    if (access(cli, R_OK) != 0) {
        fprintf(stderr, "Node.js CLI data is missing: %s\n", cli);
        return 1;
    }
    NodePolicy policy;
    if (initialize_node_policy(paths, &policy) != 0) {
        return 1;
    }
    size_t count = (size_t)argument_count + policy.count + 2;
    char **node_arguments = allocate_arguments(count);
    if (node_arguments == NULL) {
        fprintf(stderr, "unable to allocate Node.js module arguments\n");
        return 1;
    }
    node_arguments[0] = (char *)paths->node;
    for (size_t index = 0; index < policy.count; index++) {
        node_arguments[index + 1] = (char *)policy.arguments[index];
    }
    node_arguments[policy.count + 1] = cli;
    for (int index = 0; index < argument_count; index++) {
        node_arguments[index + policy.count + 2] = arguments[index];
    }
    execv(paths->node, node_arguments);
    fprintf(stderr, "execv %s: %s\n", paths->node, strerror(errno));
    free(node_arguments);
    return 127;
}


static void print_usage(const char *program) {
    fprintf(stderr,
            "usage: %s {python|python3|pip|pip3|node|npm|npx|probe} [args...]\n",
            program);
}


int main(int argc, char **argv) {
    RuntimePaths paths;
    if (initialize_paths(&paths) != 0 || prepare_environment(&paths) != 0) {
        return 1;
    }

    const char *applet = base_name(argv[0]);
    int applet_index = 0;
    if (strcmp(applet, LAUNCHER_NAME) == 0 ||
        strcmp(applet, "vantaloom-runtime") == 0) {
        if (argc < 2) {
            print_usage(argv[0]);
            return 2;
        }
        applet_index = 1;
        applet = argv[applet_index];
    }
    int argument_count = argc - applet_index;
    char **arguments = argv + applet_index;

    if (strcmp(applet, "python") == 0 || strcmp(applet, "python3") == 0) {
        return run_python(&paths, argument_count, arguments);
    }
    if (strcmp(applet, "pip") == 0 || strcmp(applet, "pip3") == 0) {
        return run_python_module(
            &paths, applet, "pip", argument_count - 1, arguments + 1);
    }
    if (strcmp(applet, "node") == 0) {
        return exec_node(&paths, argument_count, arguments);
    }
    if (strcmp(applet, "npm") == 0) {
        return exec_node_module(
            &paths, "node/lib/node_modules/npm/bin/npm-cli.js",
            argument_count - 1, arguments + 1);
    }
    if (strcmp(applet, "npx") == 0) {
        return exec_node_module(
            &paths, "node/lib/node_modules/npm/bin/npx-cli.js",
            argument_count - 1, arguments + 1);
    }
    if (strcmp(applet, "probe") == 0) {
        printf("{\"protocolVersion\":1,\"python\":\"3.14.6\","
               "\"node\":\"24.18.0\",\"npm\":\"11.16.0\"}\n");
        return 0;
    }
    print_usage(argv[0]);
    return 2;
}
