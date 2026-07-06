package com.termux.app.operit.runtime;

import com.termux.app.openhouse.servicecontrol.ServiceManagerClient;
import com.termux.app.openhouse.servicecontrol.ServiceManagerResult;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

public final class OperitRuntimeBridge {

    private static final long DEFAULT_COMMAND_TIMEOUT_MS = 15_000L;
    private static final long MAX_SHORT_COMMAND_TIMEOUT_MS = 60_000L;
    private static final long STREAM_DRAIN_TIMEOUT_MS = 1_500L;
    private static final int COMMAND_TIMEOUT_EXIT_CODE = 124;
    private static final int COMMAND_POLICY_EXIT_CODE = 125;
    private static final int COMMAND_ERROR_EXIT_CODE = 1;
    private static final int COMMAND_UNSUPPORTED_EXIT_CODE = 126;
    private static final int COMMAND_NOT_FOUND_EXIT_CODE = 127;
    private static final int HTTP_CONNECT_TIMEOUT_MS = 2_500;
    private static final int HTTP_READ_TIMEOUT_MS = 7_000;
    private static final long UBUNTU_LOGIN_PROBE_TIMEOUT_MS = 10_000L;
    private static final int MAX_CAPTURE_CHARS = 256 * 1024;
    private static final String UBUNTU_CURRENT_ROOTFS_RELATIVE_PATH =
        "var/lib/proot-distro/containers/ubuntu/rootfs";
    private static final String UBUNTU_LEGACY_ROOTFS_RELATIVE_PATH =
        "var/lib/proot-distro/installed-rootfs/ubuntu";
    private static final Pattern BACKGROUND_OPERATOR_PATTERN = Pattern.compile("(?<![>&])&(?![&>])");
    private static final Pattern DAEMONIZER_COMMAND_PATTERN = Pattern.compile(
        "(?i)(?:^|[;|(){}]|&&|\\|\\|)\\s*(?:nohup|setsid|daemon|disown|supervisord)(?=$|[\\s;|(){}])"
    );
    private static final Pattern SERVICE_MANAGER_SHELL_PATTERN = Pattern.compile(
        "(?i)(?:^|[;|(){}]|&&|\\|\\|)\\s*service-manager(?=$|[\\s;|(){}])"
    );
    private static final Pattern SERVICE_START_COMMAND_PATTERN = Pattern.compile(
        "(?i)(?:^|[;|(){}]|&&|\\|\\|)\\s*(?:systemctl\\s+(?:start|restart|enable)"
            + "|service\\s+[^\\s;|(){}]+\\s+(?:start|restart)"
            + "|pm2\\s+(?:start|restart|resurrect|startup))(?=$|[\\s;|(){}])"
    );

    public OperitCommandResult executeTermux(String command, long timeoutMs) {
        return execute(OperitRuntimeTarget.TERMUX, command, timeoutMs);
    }

    public OperitCommandResult executeUbuntu(String command, long timeoutMs) {
        return execute(OperitRuntimeTarget.UBUNTU, command, timeoutMs);
    }

    public OperitCommandResult execute(OperitRuntimeTarget target, String command) {
        return execute(target, command, DEFAULT_COMMAND_TIMEOUT_MS);
    }

    public OperitServiceManagerResult getServiceManagerHealth() {
        long startedAt = System.currentTimeMillis();
        HttpURLConnection connection = null;
        String healthUrl = serviceManagerBaseUrl() + "/api/v1/health";
        try {
            URL url = new URL(healthUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");

            int code = connection.getResponseCode();
            String body = readConnectionBody(connection, code >= 400);
            boolean success = code >= 200 && code < 400;
            String message = success
                ? "service-manager health is reachable."
                : "service-manager health returned HTTP " + code + ".";
            return new OperitServiceManagerResult(
                success,
                code,
                healthUrl,
                body,
                message,
                "",
                "",
                "",
                -1,
                "",
                success ? "" : combineErrors(message, body),
                elapsedMs(startedAt)
            );
        } catch (SocketTimeoutException e) {
            return serviceManagerError(
                healthUrl,
                "service-manager health request timed out.",
                e,
                startedAt
            );
        } catch (Exception e) {
            return serviceManagerError(
                healthUrl,
                "service-manager health request failed.",
                e,
                startedAt
            );
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public OperitServiceManagerResult getServiceManagerStatus(String serviceId) {
        long startedAt = System.currentTimeMillis();
        String serviceManagerUrl = serviceManagerBaseUrl();
        String cleanServiceId = ServiceManagerClient.sanitizeServiceId(serviceId);
        if (cleanServiceId.isEmpty()) {
            return new OperitServiceManagerResult(
                false,
                0,
                serviceManagerUrl,
                "",
                "Service id is invalid.",
                "",
                "",
                "",
                -1,
                "",
                "Service id is invalid.",
                elapsedMs(startedAt)
            );
        }

        ServiceManagerResult result = new ServiceManagerClient(serviceManagerUrl)
            .getStatus(cleanServiceId);
        String error = result.success ? "" : result.message;
        return new OperitServiceManagerResult(
            result.success,
            result.code,
            serviceManagerUrl,
            result.body,
            result.message,
            cleanServiceId,
            result.state,
            result.provider,
            result.pid == null ? -1 : result.pid,
            result.url,
            error,
            elapsedMs(startedAt)
        );
    }

    private static String serviceManagerBaseUrl() {
        return ServiceManagerClient.resolveConfiguredBaseUrl();
    }

    public OperitCommandResult execute(OperitRuntimeTarget target, String command, long timeoutMs) {
        long startedAt = System.currentTimeMillis();
        String cleanCommand = trim(command);
        if (target == null) {
            return commandError(null, cleanCommand, COMMAND_ERROR_EXIT_CODE, "Runtime target is required.", startedAt);
        }
        if (target == OperitRuntimeTarget.ANDROID) {
            return unsupportedCommandTarget(
                target,
                cleanCommand,
                "Android shell command execution is not implemented by this runtime bridge yet.",
                startedAt
            );
        }
        if (target == OperitRuntimeTarget.SERVICE_MANAGER) {
            return unsupportedCommandTarget(
                target,
                cleanCommand,
                "SERVICE_MANAGER is not a shell execution target. Use getServiceManagerHealth() or getServiceManagerStatus(serviceId).",
                startedAt
            );
        }
        if (target != OperitRuntimeTarget.TERMUX && target != OperitRuntimeTarget.UBUNTU) {
            return unsupportedCommandTarget(target, cleanCommand, "Unsupported runtime target: " + target + ".", startedAt);
        }
        if (cleanCommand.isEmpty()) {
            return commandError(target, "", COMMAND_ERROR_EXIT_CODE, "Command is empty.", startedAt);
        }

        long effectiveTimeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_COMMAND_TIMEOUT_MS;
        if (effectiveTimeoutMs > MAX_SHORT_COMMAND_TIMEOUT_MS) {
            return commandError(
                target,
                cleanCommand,
                COMMAND_POLICY_EXIT_CODE,
                "Runtime shell execution is limited to short commands. Use service-manager typed APIs for long-running services.",
                startedAt
            );
        }

        String policyError = validateShortLivedCommand(cleanCommand);
        if (!policyError.isEmpty()) {
            return commandError(target, cleanCommand, COMMAND_POLICY_EXIT_CODE, policyError, startedAt);
        }

        File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash");
        if (!bash.isFile()) {
            return commandError(
                target,
                cleanCommand,
                COMMAND_NOT_FOUND_EXIT_CODE,
                "Termux bash is not installed: " + bash.getAbsolutePath(),
                startedAt
            );
        }

        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        if (!home.isDirectory()) {
            return commandError(
                target,
                cleanCommand,
                COMMAND_NOT_FOUND_EXIT_CODE,
                "Termux home directory is not available: " + home.getAbsolutePath(),
                startedAt
            );
        }

        String shellCommand = cleanCommand;
        if (target == OperitRuntimeTarget.UBUNTU) {
            File prootDistro = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "proot-distro");
            if (!prootDistro.isFile()) {
                return commandError(
                    target,
                    cleanCommand,
                    COMMAND_NOT_FOUND_EXIT_CODE,
                    "Ubuntu terminal is not ready yet because proot-distro is not installed: "
                        + prootDistro.getAbsolutePath()
                        + ". Complete the first-run Ubuntu setup, then retry.",
                    startedAt
                );
            }

            File ubuntuRootfs = findUbuntuRootfsDirectory();
            if (!ubuntuRootfs.isDirectory()) {
                return commandError(
                    target,
                    cleanCommand,
                    COMMAND_NOT_FOUND_EXIT_CODE,
                    "Ubuntu terminal is not ready yet because the Ubuntu rootfs is not installed. "
                        + "Checked: "
                        + describeUbuntuRootfsCandidates()
                        + ". Complete the first-run Ubuntu setup, then retry.",
                    startedAt
                );
            }

            String loginError = probeUbuntuLogin(bash, home, prootDistro, effectiveTimeoutMs);
            if (!loginError.isEmpty()) {
                return commandError(target, cleanCommand, COMMAND_ERROR_EXIT_CODE, loginError, startedAt);
            }

            shellCommand = shellQuote(prootDistro.getAbsolutePath())
                + " login ubuntu -- bash -lc "
                + shellQuote(cleanCommand);
        }

        Process process = null;
        ExecutorService streamReaders = Executors.newFixedThreadPool(2);
        Future<StreamCapture> stdoutFuture = null;
        Future<StreamCapture> stderrFuture = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                bash.getAbsolutePath(),
                "-lc",
                shellCommand
            );
            processBuilder.directory(home);
            processBuilder.redirectErrorStream(false);
            applyTermuxEnvironment(processBuilder.environment());

            process = processBuilder.start();
            stdoutFuture = streamReaders.submit(new StreamReader(process.getInputStream(), "stdout"));
            stderrFuture = streamReaders.submit(new StreamReader(process.getErrorStream(), "stderr"));

            boolean finished = process.waitFor(effectiveTimeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(STREAM_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }

            StreamCapture stdout = collectStream(stdoutFuture);
            StreamCapture stderr = collectStream(stderrFuture);
            String streamError = combineErrors(stdout.error, stderr.error);
            int exitCode = finished ? process.exitValue() : COMMAND_TIMEOUT_EXIT_CODE;
            String error = finished
                ? streamError
                : combineErrors("Command timed out after " + effectiveTimeoutMs + " ms.", streamError);

            return new OperitCommandResult(
                cleanCommand,
                target,
                stdout.text,
                stderr.text,
                exitCode,
                error,
                !finished,
                elapsedMs(startedAt)
            );
        } catch (Exception e) {
            return commandError(target, cleanCommand, COMMAND_ERROR_EXIT_CODE, compactException(e), startedAt);
        } finally {
            if (process != null) {
                process.destroy();
            }
            streamReaders.shutdownNow();
        }
    }

    private static String validateShortLivedCommand(String command) {
        if (BACKGROUND_OPERATOR_PATTERN.matcher(command).find()) {
            return "Background shell operators are not allowed through Operit runtime shell execution. Use service-manager typed APIs for long-running services.";
        }
        if (DAEMONIZER_COMMAND_PATTERN.matcher(command).find()) {
            return "Daemonizer commands are not allowed through Operit runtime shell execution. Use service-manager typed APIs for long-running services.";
        }
        if (SERVICE_MANAGER_SHELL_PATTERN.matcher(command).find()) {
            return "service-manager shell commands are not allowed here. Use getServiceManagerHealth() or getServiceManagerStatus(serviceId).";
        }
        if (SERVICE_START_COMMAND_PATTERN.matcher(command).find()) {
            return "Service start/restart commands are not allowed through Operit runtime shell execution. Use service-manager typed APIs for long-running services.";
        }
        return "";
    }

    private static void applyTermuxEnvironment(Map<String, String> environment) {
        environment.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        environment.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        environment.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
        environment.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        environment.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        environment.put("LANG", "C.UTF-8");
        environment.put("OPENHOUSEAI_NO_AUTO_UBUNTU", "1");
        environment.put("SMALLPHONEAI_NO_AUTO_UBUNTU", "1");
        environment.put("TERMUX_NO_AUTO_UBUNTU", "1");
    }

    private static File findUbuntuRootfsDirectory() {
        for (File candidate : ubuntuRootfsCandidates()) {
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        return ubuntuRootfsCandidates()[0];
    }

    private static File[] ubuntuRootfsCandidates() {
        return new File[] {
            new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, UBUNTU_CURRENT_ROOTFS_RELATIVE_PATH),
            new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, UBUNTU_LEGACY_ROOTFS_RELATIVE_PATH)
        };
    }

    private static String describeUbuntuRootfsCandidates() {
        File[] candidates = ubuntuRootfsCandidates();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < candidates.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(candidates[i].getAbsolutePath());
        }
        return builder.toString();
    }

    private static String probeUbuntuLogin(File bash, File home, File prootDistro, long effectiveTimeoutMs) {
        long probeTimeoutMs = Math.min(effectiveTimeoutMs, UBUNTU_LOGIN_PROBE_TIMEOUT_MS);
        Process process = null;
        ExecutorService streamReaders = Executors.newFixedThreadPool(2);
        Future<StreamCapture> stdoutFuture = null;
        Future<StreamCapture> stderrFuture = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                bash.getAbsolutePath(),
                "-lc",
                shellQuote(prootDistro.getAbsolutePath()) + " login ubuntu -- true"
            );
            processBuilder.directory(home);
            processBuilder.redirectErrorStream(false);
            applyTermuxEnvironment(processBuilder.environment());

            process = processBuilder.start();
            stdoutFuture = streamReaders.submit(new StreamReader(process.getInputStream(), "stdout"));
            stderrFuture = streamReaders.submit(new StreamReader(process.getErrorStream(), "stderr"));

            boolean finished = process.waitFor(probeTimeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(STREAM_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }

            StreamCapture stdout = collectStream(stdoutFuture);
            StreamCapture stderr = collectStream(stderrFuture);
            String streamError = combineErrors(stdout.error, stderr.error);
            if (!finished) {
                return combineErrors(
                    "Ubuntu terminal login failed because proot-distro login ubuntu -- true timed out after "
                        + probeTimeoutMs
                        + " ms.",
                    streamError
                );
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return streamError;
            }

            String processOutput = combineErrors(stderr.text, stdout.text);
            String details = combineErrors(processOutput, streamError);
            return combineErrors(
                "Ubuntu terminal login failed because proot-distro login ubuntu -- true exited with code "
                    + exitCode
                    + ".",
                details
            );
        } catch (Exception e) {
            return "Ubuntu terminal login failed before running the requested command: " + compactException(e);
        } finally {
            if (process != null) {
                process.destroy();
            }
            streamReaders.shutdownNow();
        }
    }

    private static OperitCommandResult commandError(
        OperitRuntimeTarget target,
        String command,
        int exitCode,
        String error,
        long startedAt
    ) {
        return new OperitCommandResult(command, target, "", "", exitCode, error, false, elapsedMs(startedAt));
    }

    private static OperitCommandResult unsupportedCommandTarget(
        OperitRuntimeTarget target,
        String command,
        String message,
        long startedAt
    ) {
        return commandError(target, command, COMMAND_UNSUPPORTED_EXIT_CODE, message, startedAt);
    }

    private static OperitServiceManagerResult serviceManagerError(
        String url,
        String message,
        Exception exception,
        long startedAt
    ) {
        String error = combineErrors(message, compactException(exception));
        return new OperitServiceManagerResult(
            false,
            0,
            url,
            "",
            message,
            "",
            "",
            "",
            -1,
            "",
            error,
            elapsedMs(startedAt)
        );
    }

    private static String readConnectionBody(HttpURLConnection connection, boolean errorBody) throws IOException {
        InputStream inputStream = errorBody ? connection.getErrorStream() : connection.getInputStream();
        if (inputStream == null) {
            return "";
        }
        return readStream(inputStream).text;
    }

    private static StreamCapture collectStream(Future<StreamCapture> future) {
        if (future == null) {
            return new StreamCapture("", "");
        }
        try {
            return future.get(STREAM_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new StreamCapture("", "Timed out while collecting process output.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StreamCapture("", "Interrupted while collecting process output.");
        } catch (ExecutionException e) {
            return new StreamCapture("", compactException(e));
        }
    }

    private static StreamCapture readStream(InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        boolean truncated = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int extraChars = line.length() + (builder.length() == 0 ? 0 : 1);
                if (builder.length() + extraChars > MAX_CAPTURE_CHARS) {
                    int remaining = Math.max(0, MAX_CAPTURE_CHARS - builder.length());
                    if (remaining > 0) {
                        if (builder.length() > 0) {
                            builder.append('\n');
                            remaining--;
                        }
                        if (remaining > 0) {
                            builder.append(line, 0, Math.min(line.length(), remaining));
                        }
                    }
                    truncated = true;
                    break;
                }
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
        }
        if (truncated) {
            builder.append("\n...output truncated.");
        }
        return new StreamCapture(builder.toString(), "");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String combineErrors(String first, String second) {
        String cleanFirst = trim(first);
        String cleanSecond = trim(second);
        if (cleanFirst.isEmpty()) {
            return cleanSecond;
        }
        if (cleanSecond.isEmpty()) {
            return cleanFirst;
        }
        return cleanFirst + "\n" + cleanSecond;
    }

    private static String compactException(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        Throwable cause = throwable instanceof ExecutionException && throwable.getCause() != null
            ? throwable.getCause()
            : throwable;
        String message = trim(cause.getMessage());
        if (message.isEmpty()) {
            return cause.getClass().getSimpleName();
        }
        return cause.getClass().getSimpleName() + ": " + message;
    }

    private static long elapsedMs(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class StreamReader implements Callable<StreamCapture> {
        private final InputStream inputStream;
        private final String streamName;

        StreamReader(InputStream inputStream, String streamName) {
            this.inputStream = inputStream;
            this.streamName = streamName;
        }

        @Override
        public StreamCapture call() {
            try {
                return readStream(inputStream);
            } catch (IOException e) {
                return new StreamCapture("", "Failed to read " + streamName + ": " + compactException(e));
            }
        }
    }

    private static final class StreamCapture {
        final String text;
        final String error;

        StreamCapture(String text, String error) {
            this.text = text == null ? "" : text;
            this.error = error == null ? "" : error;
        }
    }
}
