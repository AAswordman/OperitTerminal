# Operit Terminal

一个在Android上运行Ubuntu环境的终端应用。

## 项目描述

这是一个Android终端应用，集成了Ubuntu环境，允许用户在Android设备上运行Linux命令和工具。

## 架构和AIDL接口

`Operit Terminal` 采用客户端-服务端（Client-Service）架构，核心功能在一个后台的 `TerminalService` 中运行。UI（例如 Activity）作为客户端，通过 AIDL (Android Interface Definition Language) 与服务进行通信。这种设计确保了即使用户切换应用，终端会话也能在后台持续运行。

### 概述

`TerminalService` 是一个 Android `Service`，它负责：

-   创建和管理多个终端会话。
-   处理命令的发送和中断信号。
-   维护每个会话的状态，包括命令历史、当前目录等。
-   通过回调机制将状态更新通知给客户端。

客户端通过绑定到该服务并使用 `ITerminalService` 接口与服务进行通信。

### 连接到服务

要使用 `TerminalService`，您需要从您的组件（例如 `Activity`）中绑定到它。

```kotlin
// 在你的 Activity 或其他组件中

private var terminalService: ITerminalService? = null
private var isServiceBound = false

private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        terminalService = ITerminalService.Stub.asInterface(service)
        isServiceBound = true
        // 服务已连接，现在可以进行交互了
        // 例如，注册回调并请求初始状态
        terminalService?.registerCallback(terminalCallback)
        terminalService?.requestStateUpdate()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        terminalService = null
        isServiceBound = false
    }
}

override fun onStart() {
    super.onStart()
    Intent(this, TerminalService::class.java).also { intent ->
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }
}

override fun onStop() {
    super.onStop()
    if (isServiceBound) {
        // 取消注册回调
        terminalService?.unregisterCallback(terminalCallback)
        unbindService(connection)
        isServiceBound = false
    }
}
```

### AIDL 接口

#### `ITerminalService.aidl`

这是与 `TerminalService` 交互的主要接口。

| 方法名                | 参数                               | 返回值   | 描述                                           |
| --------------------- | ---------------------------------- | -------- | ---------------------------------------------- |
| `createSession`       | -                                  | `String` | 创建一个新的终端会话，并返回其唯一 ID。        |
| `switchToSession`     | `in String sessionId`              | `void`   | 切换到指定 ID 的会话。                         |
| `closeSession`        | `in String sessionId`              | `void`   | 关闭指定 ID 的会话。                           |
| `sendCommand`         | `in String command`                | `void`   | 向当前会话发送一个命令。                       |
| `sendInterruptSignal` | -                                  | `void`   | 向当前会话发送中断信号 (Ctrl+C)。              |
| `registerCallback`    | `in ITerminalCallback callback`    | `void`   | 注册一个回调以接收终端状态更新。               |
| `unregisterCallback`  | `in ITerminalCallback callback`    | `void`   | 取消注册一个回调。                             |
| `requestStateUpdate`  | -                                  | `void`   | 请求立即获取一次最新的终端状态。               |

#### `ITerminalCallback.aidl`

这是一个单向（`oneway`）接口，用于从服务接收状态更新。客户端需要实现此接口。

| 方法名           | 参数                                   | 描述                                     |
| ---------------- | -------------------------------------- | ---------------------------------------- |
| `onStateUpdated` | `in TerminalStateParcelable state`     | 当终端状态发生变化时由服务调用此方法。   |

### 数据模型

AIDL 接口使用以下 `Parcelable` 对象来传输数据：

-   `TerminalStateParcelable`: 表示整个终端的完整状态，包括所有会话列表和当前会话ID。
-   `TerminalSessionDataParcelable`: 表示单个终端会话的状态，包括其 ID、命令历史、当前目录等。
-   `CommandHistoryItemParcelable`: 表示一条命令历史记录，包括提示符、输入的命令和命令的输出。

### 使用示例

以下是一个实现 `ITerminalCallback` 并与服务交互的简化示例：

```kotlin
// 在你的 Activity 或 ViewModel 中

private val terminalCallback = object : ITerminalCallback.Stub() {
    override fun onStateUpdated(state: TerminalStateParcelable?) {
        // 在主线程上处理状态更新
        activity?.runOnUiThread {
            state?.let {
                // 更新你的 UI
                // 例如: updateUi(it)
                Log.d("TerminalClient", "New state received. Current session: ${it.currentSessionId}")
            }
        }
    }
}

// 创建一个新的会话
fun createNewSession() {
    if (isServiceBound) {
        val newSessionId = terminalService?.createSession()
        Log.d("TerminalClient", "Created new session: $newSessionId")
    }
}

// 发送命令
fun sendCommandToTerminal(command: String) {
    if (isServiceBound) {
        terminalService?.sendCommand(command)
    }
}
```

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
