# Operit Terminal

An Android terminal application that runs a Ubuntu environment.

## Project Description

This is an Android terminal application that integrates a Ubuntu environment, allowing users to run Linux commands and tools on their Android devices.

## Technology Stack

-   **UI**: Built entirely with [Jetpack Compose](https://developer.android.com/jetpack/compose), Android's modern toolkit for building native UI.
-   **Architecture**: Follows a reactive architecture, using [Kotlin Flows](https://developer.android.com/kotlin/flow) to handle state management and event propagation between the core logic and the UI.
-   **Asynchronous Programming**: Utilizes [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) for managing background threads and asynchronous operations.
-   **Modularity**: The project is divided into an `app` module for the UI and a `terminal-core` module (as a Git Submodule) for the backend logic.

## Project Structure

本应用采用模块化设计，主要分为以下两个部分：

-   **`app` 模块**: 包含应用的用户界面（UI）和与 Android 系统交互的主要逻辑。
-   **`terminal-core` 模块 (Git Submodule)**: 这是一个独立的模块，包含了终端的核心功能，如会话管理、命令执行和与底层 shell 的交互。它被设计为一个可重用的组件，并通过 AIDL 接口与主 `app` 模块通信。

关于如何克隆和更新包含子模块的仓库，请参考下面的 “获取源码” 部分。

## Architecture Overview

`Operit Terminal` is built on a modular architecture centered around the `TerminalManager` class, which resides in the `terminal-core` module.

-   **`TerminalManager` (in `terminal-core`)**: This singleton class is the heart of the application. It manages all terminal sessions, processes commands, and holds the entire state of the terminal (e.g., sessions, command history, current directory). It exposes this state reactively using Kotlin Flows.

-   **`MainActivity` (in `app`)**: The main UI of the application, built with Jetpack Compose. It directly interacts with the `TerminalManager` to send commands and listens to its Kotlin Flows (`collectAsState`) to automatically update the UI whenever the terminal state changes.

-   **`TerminalService` and AIDL (in `terminal-core`)**: While the current implementation has the UI and the core logic running in the same process, the `terminal-core` module also includes a `TerminalService`. This service exposes the `TerminalManager`'s functionality through an AIDL interface, enabling robust background execution and inter-process communication (IPC). This design makes it possible to run the terminal session independently of the UI lifecycle.

### AIDL Interface Details

The AIDL interface is defined for communication with the `TerminalService`.

#### `ITerminalService.aidl`

这是与 `TerminalService` 交互的主要接口。

| 方法名                | 参数                               | 返回值   | 描述                                           |
| --------------------- | ---------------------------------- | -------- | ---------------------------------------------- |
| `createSession`       | -                                  | `String` | 创建一个新的终端会话，并返回其唯一 ID。        |
| `switchToSession`     | `in String sessionId`              | `void`   | 切换到指定 ID 的会话。                         |
| `closeSession`        | `in String sessionId`              | `void`   | 关闭指定 ID 的会话。                           |
| `sendCommand`         | `in String command`                | `void`   | 向当前会话发送一个命令。                       |
| `sendInterruptSignal` | -                                  | `void`   | 向当前会话发送中断信号 (Ctrl+C)。              |
| `registerCallback`    | `in ITerminalCallback callback`    | `void`   | 注册一个回调以接收终端事件更新。               |
| `unregisterCallback`  | `in ITerminalCallback callback`    | `void`   | 取消注册一个回调。                             |
| `requestStateUpdate`  | -                                  | `void`   | Requests an immediate, one-time update of the latest terminal state. |

#### `ITerminalCallback.aidl`

这是一个单向（`oneway`）接口，用于从服务接收事件更新。客户端需要实现此接口。

| 方法名                        | 参数                                      | 描述                                     |
| ---------------------------- | ----------------------------------------- | ---------------------------------------- |
| `onCommandExecutionUpdate`   | `in CommandExecutionEvent event`         | 当命令执行过程中有输出更新时调用此方法。   |
| `onSessionDirectoryChanged`  | `in SessionDirectoryEvent event`         | This method is called when the current directory of a session changes.     |

### Data Models

AIDL 接口使用以下事件对象来传输数据：

#### `CommandExecutionEvent`
表示命令执行过程中的事件，包含以下字段：
-   `commandId: String`: 命令的唯一标识符
-   `sessionId: String`: 执行命令的会话ID
-   `outputChunk: String`: 命令执行过程中的输出片段
-   `isCompleted: Boolean`: 命令是否执行完毕

#### `SessionDirectoryEvent`
表示会话目录变化事件，包含以下字段：
-   `sessionId: String`: 会话的唯一标识符
-   `currentDirectory: String`: The session's new current working directory

### UI and State Handling Example

The UI in `MainActivity` is built with Jetpack Compose and subscribes to state changes from `TerminalManager` using Kotlin Flows. This creates a reactive connection where the UI automatically recomposes when data changes.

Here is a simplified conceptual example of how the UI collects state:

```kotlin
// In MainActivity's Composable content

// Get the TerminalManager instance
val terminalManager = remember { TerminalManager.getInstance(context) }

// Collect state from Flows
val sessions by terminalManager.sessions.collectAsState(initial = emptyList())
val currentSessionId by terminalManager.currentSessionId.collectAsState(initial = null)
val commandHistory by terminalManager.commandHistory.collectAsState(initial = SnapshotStateList())
val currentDirectory by terminalManager.currentDirectory.collectAsState(initial = "$ ")

// The UI will automatically update when any of these state holders change.
TerminalScreen(
    sessions = sessions,
    currentSessionId = currentSessionId,
    commandHistory = commandHistory,
    currentDirectory = currentDirectory,
    // ... other parameters and event handlers
)
```

This reactive approach simplifies UI logic, as it doesn't need to manually request updates. It just observes the state provided by `TerminalManager`.

## 构建配置

### 密钥配置

为了构建发布版本，需要配置签名密钥。请按照以下步骤操作：

1. 在项目根目录创建 `keystore.properties` 文件
2. 添加以下配置项（请替换为你的实际值）：

```properties
RELEASE_KEY_ALIAS=你的密钥别名
RELEASE_KEY_PASSWORD=你的密钥密码
RELEASE_STORE_FILE=你的密钥库文件路径
RELEASE_STORE_PASSWORD=你的密钥库密码
```

**注意：**
- `keystore.properties` 文件包含敏感信息，不应提交到版本控制系统
- 该文件已被添加到 `.gitignore` 中，确保不会意外提交
- 如果没有配置密钥，debug版本仍可正常构建和运行

### Android SDK配置

项目使用 `local.properties` 文件配置Android SDK路径：
- 该文件由Android Studio自动生成
- 包含本地SDK路径配置
- 不应提交到版本控制系统

## 许可证

遵循 GPLv3 协议。

## 获取源码

由于本项目使用了 Git Submodule 来管理核心依赖 (`terminal-core`)，请使用以下命令来克隆仓库以确保所有模块都被正确下载：

```bash
git clone --recurse-submodules https://github.com/your-username/your-repository.git
```

如果你已经克隆了仓库但没有初始化子模块，可以运行以下命令：

```bash
git submodule update --init --recursive
```
