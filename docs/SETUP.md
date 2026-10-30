# 跨机器启动与开发环境

## 选择入口

| 需求 | 入口 | 必需环境 |
| --- | --- | --- |
| Windows 上直接使用 | GitHub Releases 的 EXE 或便携 ZIP | 不需要另装 Java、Maven、Node.js |
| 拉取源码运行网站 | `docker compose up -d --build` | Docker Engine + Compose v2；Windows/macOS 可用 Docker Desktop |
| 修改代码或 IDE 调试 | Windows `start.cmd`；macOS/Linux `bash start.sh` | Windows PowerShell 5.1；或 Bash、curl、tar、unzip 及 SHA-256 工具 |

Windows 桌面安装说明见 [INSTALL-WINDOWS.md](INSTALL-WINDOWS.md)。安装包包含 Java；源码启动器会在需要时准备 JDK。桌面包需要按目标操作系统分别构建，目前发布流程提供 Windows 包。

## 源码一键启动

在项目根目录运行，Windows 可直接双击 `start.cmd`：

```powershell
.\start.cmd
```

macOS / Linux：

```bash
bash start.sh
```

启动器依次检查 `.tools/jdk-21`、项目已下载的固定版本 Temurin、`JAVA_HOME`、系统 Java；macOS 也会检查系统注册的 JDK 21。Java 和 javac 都必须为 21，旧版本、只有 JRE 或无效路径不会被采用。找不到合适 JDK 时，脚本根据系统和 CPU 架构下载 `scripts/jdk-distributions.tsv` 中指定的官方发行包，校验 SHA-256 后才解压使用。

自动下载支持 Windows、macOS、Linux glibc 的 x64 / ARM64。Alpine/musl 及其他架构请自行安装 JDK 21 或使用 Docker。不同架构的发行包可能采用不同补丁版本，具体以清单为准。已有可用 JDK 21 时会复用它；需要统一到清单中的补丁版本时，可将相应 JDK 放入 `.tools/jdk-21`。

Maven Wrapper 固定使用 Maven 3.9.11，并校验 ZIP 的 SHA-256。启动器把 Maven 发行包缓存到 `.tools/maven-user-home`，依赖缓存位于 `.mvn/repository`。直接运行 Wrapper 默认使用用户的 Maven 缓存，需要自行设置正确的 `JAVA_HOME`。这些缓存和 JDK 均不提交 Git。脚本仅修改启动进程的环境，不更改系统环境变量。

首次运行需要联网访问 GitHub 和 Maven Central；后续复用缓存。新分支引入的依赖仍可能需要下载。前端已随项目打包，启动网站不需要 Node.js。

仅准备工具并显示版本：

```powershell
.\start.cmd -PrepareOnly
```

```bash
bash start.sh --prepare-only
```

执行验证或打包时，同一入口可以传递 Maven 参数：

```powershell
.\start.cmd --batch-mode verify
.\start.cmd -DskipTests package
```

```bash
bash start.sh --batch-mode verify
bash start.sh -DskipTests package
```

Ubuntu/Debian 若缺少基础工具，安装 `curl ca-certificates tar unzip`。macOS 通常自带这些工具。`unzip` 必须可用，以保证 Wrapper 下载的是校验值对应的 ZIP。启动器在需要时为当前进程选择 `C.UTF-8` 或 `en_US.UTF-8` locale，避免 Java 无法读取中文路径；极简系统需要提供其中一种 UTF-8 locale。

## Docker 启动

```bash
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 cryptobench
docker compose down
```

不需要先执行 Maven，也不需要本地 `target`。Docker 构建阶段使用固定的 Java 21 镜像和项目 Maven Wrapper 编译；运行阶段仅包含 Java 运行环境与应用。BuildKit 缓存会加速重复构建。

默认访问 `http://127.0.0.1:4173`；数据库保存在项目 `var/`，停止或重建容器不会清空数据。先停止服务再备份数据目录。普通 Compose 不启用 Docker Agent 执行；沙箱方式见 [docker/README.md](../docker/README.md)，需要允许应用访问 Docker 引擎。

## 常见问题

### 下载失败或需要代理

JDK 下载失败会显示错误和手动安装入口，不会自动使用校验失败的文件。可以从 Adoptium 官方网站下载适合操作系统、架构的完整 JDK 21，解压并让 `.tools/jdk-21/bin/java`（Windows 为 `java.exe`）存在；macOS 下载包需使用其中的 `Contents/Home` 作为 JDK 根目录。也可以设置 `JAVA_HOME` 为安装路径。

JDK 下载代理示例：

```powershell
$env:HTTPS_PROXY = 'http://127.0.0.1:7890'
.\start.cmd
```

```bash
export https_proxy=http://127.0.0.1:7890
bash start.sh
```

Maven 依赖代理或镜像应在自己的 `~/.m2/settings.xml` 配置，不要将个人代理、密码或绝对路径提交到仓库。Wrapper 发行包下载可以使用 `MVNW_REPOURL` 指定可信 Maven 镜像，保持发行包 SHA-256 不变。Docker 的代理需在 Docker Desktop/Engine 中配置，容器内的 `127.0.0.1` 不是宿主机。

设置 `CRYPTOBENCH_NO_JDK_DOWNLOAD=1` 可禁止自动下载 JDK，缺少 JDK 时立即报错。它不禁止 Maven 下载；完整离线构建还需要预先缓存 Maven 及依赖，并传入 Maven 的 `-o` 参数。

### 端口已占用

源码启动可设置 `PORT`：

```powershell
$env:PORT = '4180'
.\start.cmd
```

```bash
PORT=4180 bash start.sh
```

Compose 使用 `HOST_PORT` 改宿主机映射，容器内仍为 4173；可在根目录 `.env` 中写 `HOST_PORT=4180`。需要不同数据目录时设置 `CRYPTOBENCH_DATA_DIR`。Compose 会读取 `.env`，直接运行 Spring Boot 不会读取。

### IDE 仍显示 Java 错误

启动脚本不会改变已经打开的 IDE 环境。先运行准备工具命令，查看输出的 `JAVA_HOME`，再在 IDEA 中将 Project SDK 和 Maven Runner JRE 设为该 JDK 21，Maven 选择项目 Wrapper。

VS Code 推荐 Red Hat Java 与 Java Debugger 扩展。将自己的 Java 21 路径配置到**用户设置**中的 `java.configuration.runtimes`，不写进共享工作区设置。Java 语言服务可能需要更高版本运行时，优先使用扩展自带运行时；语言服务用的 Java 与项目 JDK 21 可以不同。切换后重新加载窗口，必要时执行 `Java: Clean Java Language Server Workspace`。

## 维护版本与验证

升级 Maven 时同时更新 `.mvn/wrapper/maven-wrapper.properties` 的 URL、SHA-256 及 `pom.xml` 的 Enforcer 范围。升级默认 JDK 时更新 `scripts/jdk-distributions.tsv` 的官方 URL、版本和校验值，并同步 Dockerfile 和 CI 的固定版本。不能只换 URL 或跳过校验。

CI 从全新检出验证 Compose，并检查独立数据目录中的账号在容器重建后仍可登录；sandbox 使用专用端到端测试。源码启动测试使用含空格和中文的临时目录，覆盖 JDK 发现、版本拒绝、下载校验及缓存复用。
