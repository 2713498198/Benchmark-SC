#!/usr/bin/env bash

cryptobench_valid_jdk() {
    [[ -n "${1:-}" && -x "$1/bin/java" && -x "$1/bin/javac" ]] || return 1
    local runtime compiler
    runtime=$("$1/bin/java" --version 2>/dev/null) || return 1
    compiler=$("$1/bin/javac" --version 2>/dev/null) || return 1
    [[ "$runtime" =~ ^(openjdk|java)\ 21[.[:space:]] && "$compiler" =~ ^javac\ 21[.[:space:]] ]]
}

initialize_cryptobench_java() {
    local root="$1" os arch entry version url checksum cached selected='' candidate system_java='' mac_java=''
    # Java's native path encoding follows the locale even with UTF-8 file.encoding.
    # A minimal Linux image often starts in ASCII, breaking non-ASCII JDK paths.
    if [[ "$(locale charmap 2>/dev/null || true)" != UTF-8 ]]; then
        if [[ "$(LC_ALL=C.UTF-8 locale charmap 2>/dev/null || true)" == UTF-8 ]]; then
            export LC_ALL=C.UTF-8
        elif [[ "$(LC_ALL=en_US.UTF-8 locale charmap 2>/dev/null || true)" == UTF-8 ]]; then
            export LC_ALL=en_US.UTF-8
        else
            echo 'A UTF-8 locale is required for portable Java paths. Install/enable C.UTF-8 or en_US.UTF-8.' >&2
            return 1
        fi
    fi
    case "$(uname -s)" in Linux) os=linux ;; Darwin) os=mac ;; *) echo 'Use start.cmd on Windows.' >&2; return 1 ;; esac
    case "$(uname -m)" in x86_64|amd64) arch=x64 ;; arm64|aarch64) arch=aarch64 ;; *) arch=unsupported ;; esac
    entry=$(awk -F '\t' -v os="$os" -v arch="$arch" '$1 == os && $2 == arch {print; exit}' "$root/scripts/jdk-distributions.tsv")
    cached=''
    if [[ -n "$entry" ]]; then
        IFS=$'\t' read -r os arch version url checksum <<< "$entry"
        cached="$root/.tools/temurin-$version-$os-$arch"
        [[ "$os" != mac ]] || cached="$cached/Contents/Home"
    fi
    if command -v java >/dev/null 2>&1; then
        system_java=$(command -v java)
        while [[ -L "$system_java" ]]; do
            candidate=$(readlink "$system_java")
            if [[ "$candidate" == /* ]]; then system_java="$candidate"; else system_java="$(dirname "$system_java")/$candidate"; fi
        done
        system_java=$(cd "$(dirname "$system_java")/.." && pwd)
    fi
    if [[ "$os" == mac && -x /usr/libexec/java_home ]]; then mac_java=$(/usr/libexec/java_home -v 21 2>/dev/null || true); fi
    for candidate in "$root/.tools/jdk-21" "$cached" "${JAVA_HOME:-}" "$system_java" "$mac_java"; do
        if cryptobench_valid_jdk "$candidate"; then selected="$candidate"; break; fi
    done
    if [[ -z "$selected" ]]; then
        if [[ "${CRYPTOBENCH_NO_JDK_DOWNLOAD:-}" == 1 ]]; then echo 'JDK 21 was not found. Set JAVA_HOME or put a full JDK 21 in .tools/jdk-21. Automatic download is disabled.' >&2; return 1; fi
        if [[ -z "$entry" ]]; then echo "No JDK download for $os/$arch. Install JDK 21 and set JAVA_HOME." >&2; return 1; fi
        local destination="$root/.tools/temurin-$version-$os-$arch"
        if [[ -e "$destination" ]]; then echo "Cached JDK is invalid: $destination. Move it aside and retry, or set JAVA_HOME." >&2; return 1; fi
        mkdir -p "$root/.tools"
        # A subshell owns staging cleanup; the user's shell traps are untouched.
        if ! (
            staging=$(mktemp -d "$root/.tools/.jdk-download-XXXXXX") || exit 1
            trap 'rm -rf -- "$staging"' EXIT
            printf 'Downloading Temurin %s (%s/%s) into .tools...\n' "$version" "$os" "$arch"
            curl --fail --location --http1.1 --retry 2 --connect-timeout 30 "$url" --output "$staging/jdk.tar.gz" || exit 1
            if command -v sha256sum >/dev/null 2>&1; then
                actual=$(sha256sum "$staging/jdk.tar.gz") || exit 1
            else
                actual=$(shasum -a 256 "$staging/jdk.tar.gz") || exit 1
            fi
            [[ "${actual%% *}" == "$checksum" ]] || { echo 'JDK SHA-256 verification failed.' >&2; exit 1; }
            mkdir "$staging/expanded" || exit 1
            tar -xzf "$staging/jdk.tar.gz" -C "$staging/expanded" || exit 1
            roots=("$staging/expanded"/*)
            jdk="${roots[0]}"
            [[ "$os" != mac ]] || jdk="$jdk/Contents/Home"
            cryptobench_valid_jdk "$jdk" || {
                echo 'Downloaded archive is not a working JDK 21. Runtime diagnostics:' >&2
                "$jdk/bin/java" --version >&2
                "$jdk/bin/javac" --version >&2
                exit 1
            }
            mv "${roots[0]}" "$destination" || exit 1
        ); then echo 'JDK setup failed. See docs/SETUP.md for proxy and manual installation instructions.' >&2; return 1; fi
        selected="$cached"
    fi
    export JAVA_HOME="$selected"
    export PATH="$JAVA_HOME/bin:$PATH"
    export MAVEN_USER_HOME="$root/.tools/maven-user-home"
    printf 'JAVA_HOME: %s\n' "$JAVA_HOME"
    "$JAVA_HOME/bin/java" --version
}
