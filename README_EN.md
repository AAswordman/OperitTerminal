# Operit Terminal

<p align="center">
  <img src="docs/9f85b39450c8616909039b66d15a475a.jpg" alt="Operit Terminal" width="300"/>
</p>

## Overview

This is a Ubuntu 24 system that runs on Android devices, integrated as a core component within the Operit application. It provides users with a fully functional and powerful mobile Linux environment. Its greatest advantage lies in its deep integration with Operit, offering a seamless development and operational experience.

## Key Features

- **Full Ubuntu 24 Environment**: Provides a desktop-class Linux experience on Android devices.
- **One-Click Environment Setup**: Built-in automated scripts simplify the environment configuration and deployment process.
- **Open AIDL Interface**: Exposes core functionalities through AIDL (`Android Interface Definition Language`), allowing other applications to securely perform inter-process communication and function calls, making it easy for developers to extend and integrate.

## Technology Stack

-   **UI**: Built entirely with [Jetpack Compose](https://developer.android.com/jetpack/compose), Android's modern toolkit for building native UI.
-   **Architecture**: Follows a reactive architecture, using [Kotlin Flows](https://developer.android.com/kotlin/flow) to handle state management and event propagation between the core logic and the UI.
-   **Asynchronous Programming**: Utilizes [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) for managing background threads and asynchronous operations.
-   **Modularity**: The project is divided into an `app` module for the UI and a `terminal-core` module (as a Git Submodule) for the backend logic.

## Project Structure

This application uses a modular design, mainly divided into the following two parts:

-   **`app` module**: Contains the application's user interface (UI) and the main logic for interacting with the Android system.
-   **`terminal-core` module (Git Submodule)**: This is an independent module that contains the core functionalities of the terminal, such as session management, command execution, and interaction with the underlying shell. It is designed as a reusable component and communicates with the main `app` module through an AIDL interface.

For how to clone and update a repository containing submodules, please refer to the "Getting the Source" section below.

## Architecture Overview

`Operit Terminal` is built on a modular architecture centered around the `TerminalManager` class, which resides in the `terminal-core` module.

-   **`TerminalManager` (in `terminal-core`)**: This singleton class is the heart of the application. It manages all terminal sessions, processes commands, and holds the entire state of the terminal (e.g., sessions, command history, current directory). It exposes this state reactively using Kotlin Flows.

-   **`MainActivity` (in `app`)**: The main UI of the application, built with Jetpack Compose. It directly interacts with the `TerminalManager` to send commands and listens to its Kotlin Flows (`collectAsState`) to automatically update the UI whenever the terminal state changes.

-   **`TerminalService` and AIDL (in `terminal-core`)**: While the current implementation has the UI and the core logic running in the same process, the `terminal-core` module also includes a `TerminalService`. This service exposes the `TerminalManager`'s functionality through an AIDL interface, enabling robust background execution and inter-process communication (IPC). This design makes it possible to run the terminal session independently of the UI lifecycle.

### AIDL Interface Details

The AIDL interface is defined for communication with the `TerminalService`.

#### `ITerminalService.aidl`

This is the main interface for interacting with the `TerminalService`.

| Method Name           | Parameters                         | Return Value | Description                                                    |
| --------------------- | ---------------------------------- | ------------ | -------------------------------------------------------------- |
| `createSession`       | -                                  | `String`     | Creates a new terminal session and returns its unique ID.      |
| `switchToSession`     | `in String sessionId`              | `void`       | Switches to the session with the specified ID.                 |
| `closeSession`        | `in String sessionId`              | `void`       | Closes the session with the specified ID.                      |
| `sendCommand`         | `in String command`                | `void`       | Sends a command to the current session.                        |
| `sendInterruptSignal` | -                                  | `void`       | Sends an interrupt signal (Ctrl+C) to the current session.     |
| `registerCallback`    | `in ITerminalCallback callback`    | `void`       | Registers a callback to receive terminal event updates.        |
| `unregisterCallback`  | `in ITerminalCallback callback`    | `void`       | Unregisters a callback.                                        |
| `requestStateUpdate`  | -                                  | `void`       | Requests an immediate, one-time update of the latest terminal state. |

#### `ITerminalCallback.aidl`

This is a one-way (`oneway`) interface for receiving event updates from the service. The client needs to implement this interface.

| Method Name                  | Parameters                         | Description                                                              |
| ---------------------------- | ---------------------------------- | ------------------------------------------------------------------------ |
| `onCommandExecutionUpdate`   | `in CommandExecutionEvent event`   | This method is called when there is an output update during command execution. |
| `onSessionDirectoryChanged`  | `in SessionDirectoryEvent event`   | This method is called when the current directory of a session changes.   |

### Data Models

The AIDL interface uses the following event objects to transfer data:

#### `CommandExecutionEvent`
Represents an event during command execution, containing the following fields:
-   `commandId: String`: The unique identifier for the command
-   `sessionId: String`: The ID of the session executing the command
-   `outputChunk: String`: A fragment of the output during command execution
-   `isCompleted: Boolean`: Whether the command has finished executing

#### `SessionDirectoryEvent`
Represents a session directory change event, containing the following fields:
-   `sessionId: String`: The unique identifier for the session
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

## Build Configuration

### Keystore Configuration

To build a release version, you need to configure a signing key. Please follow these steps:

1. Create a `keystore.properties` file in the project root directory.
2. Add the following configuration items (please replace them with your actual values):

```properties
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password
RELEASE_STORE_FILE=path_to_your_keystore_file
RELEASE_STORE_PASSWORD=your_store_password
```

**Note:**
- The `keystore.properties` file contains sensitive information and should not be committed to the version control system.
- This file has been added to `.gitignore` to ensure it is not accidentally committed.
- If the key is not configured, the debug version can still be built and run normally.

### Android SDK Configuration

The project uses the `local.properties` file to configure the Android SDK path:
- This file is automatically generated by Android Studio.
- It contains the local SDK path configuration.
- It should not be committed to the version control system.

## License

This project is licensed under the GPLv3 License.

## Getting the Source

Since this project uses Git Submodules to manage the core dependency (`terminal-core`), please use the following command to clone the repository to ensure all modules are downloaded correctly:

```bash
git clone --recurse-submodules https://github.com/your-username/your-repository.git
```

If you have already cloned the repository but have not initialized the submodules, you can run the following command:

```bash
git submodule update --init --recursive
``` 