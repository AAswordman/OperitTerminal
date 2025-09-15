#include <jni.h>
#include <string.h>
#include <unistd.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <android/log.h>
#include <termios.h>

#ifdef __linux__
#include <pty.h>
#elif __APPLE__
#include <util.h>
#endif

#define TAG "PtyJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jintArray JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_createSubprocess(JNIEnv *env, jobject thiz,
                                                                          jobjectArray cmdarray,
                                                                          jobjectArray envarray,
                                                                          jstring workingDir) {
    int master_fd;
    pid_t pid;

    struct termios tt;
    memset(&tt, 0, sizeof(tt));
    tt.c_iflag = ICRNL | IXON | IXANY;
    tt.c_oflag = OPOST | ONLCR;
    tt.c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | ECHONL | IEXTEN;
    tt.c_cflag = CS8 | CREAD;
    tt.c_cc[VINTR]    = 'C' - '@';
    tt.c_cc[VQUIT]    = '\\' - '@';
    tt.c_cc[VERASE]   = 0x7f; // DEL
    tt.c_cc[VKILL]    = 'U' - '@';
    tt.c_cc[VEOF]     = 'D' - '@';
    tt.c_cc[VSTOP]    = 'S' - '@';
    tt.c_cc[VSUSP]    = 'Z' - '@';
    tt.c_cc[VSTART]   = 'Q' - '@';
    tt.c_cc[VMIN]     = 1;
    tt.c_cc[VTIME]    = 0;


    pid = forkpty(&master_fd, NULL, &tt, NULL);

    if (pid < 0) {
        LOGE("forkpty failed");
        return NULL;
    }

    if (pid == 0) { // Child process
        const char *cwd = (*env)->GetStringUTFChars(env, workingDir, 0);
        if (chdir(cwd) != 0) {
            LOGE("chdir to %s failed", cwd);
            exit(1);
        }
        (*env)->ReleaseStringUTFChars(env, workingDir, cwd);

        int env_len = (*env)->GetArrayLength(env, envarray);
        char **envp = (char **) malloc(sizeof(char *) * (env_len + 1));
        for (int i = 0; i < env_len; i++) {
            jstring j_env_str = (jstring) (*env)->GetObjectArrayElement(env, envarray, i);
            const char *env_str = (*env)->GetStringUTFChars(env, j_env_str, 0);
            envp[i] = strdup(env_str);
            (*env)->ReleaseStringUTFChars(env, j_env_str, env_str);
        }
        envp[env_len] = NULL;
        
        int cmd_len = (*env)->GetArrayLength(env, cmdarray);
        char **argv = (char **) malloc(sizeof(char *) * (cmd_len + 1));
        for (int i = 0; i < cmd_len; i++) {
            jstring j_cmd_str = (jstring) (*env)->GetObjectArrayElement(env, cmdarray, i);
            const char *cmd_str = (*env)->GetStringUTFChars(env, j_cmd_str, 0);
            argv[i] = strdup(cmd_str);
            (*env)->ReleaseStringUTFChars(env, j_cmd_str, cmd_str);
        }
        argv[cmd_len] = NULL;

        execvpe(argv[0], argv, envp);

        // execvpe should not return
        LOGE("execvpe failed");
        exit(1);
    } else { // Parent process
        jintArray result = (*env)->NewIntArray(env, 2);
        if (result == NULL) {
            return NULL; // out of memory error thrown
        }
        jint fill[2];
        fill[0] = pid;
        fill[1] = master_fd;
        (*env)->SetIntArrayRegion(env, result, 0, 2, fill);
        return result;
    }
}

JNIEXPORT jint JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_waitFor(JNIEnv *env, jobject thiz, jint pid) {
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    return -1;
} 