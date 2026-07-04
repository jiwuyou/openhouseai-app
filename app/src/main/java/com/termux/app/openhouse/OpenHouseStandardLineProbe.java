package com.termux.app.openhouse;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OpenHouseStandardLineProbe implements AutoCloseable {

    public static final long DEFAULT_TOTAL_DEADLINE_MS = 30_000L;
    public static final long DEFAULT_MIN_BYTES_PER_SECOND = 32L * 1024L;

    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 4_000;
    private static final int MAX_PROBE_BYTES = 512 * 1024;
    private static final int MIN_THROUGHPUT_SAMPLE_BYTES = 16 * 1024;
    private static final String USER_AGENT = "OpenHouseAI-LineProbe/1.0";
    private static final List<CategorySpec> CATEGORY_SPECS = buildCategorySpecs();

    private final long totalDeadlineMs;
    private final long minimumBytesPerSecond;
    private final ExecutorService workerExecutor;

    public OpenHouseStandardLineProbe() {
        this(DEFAULT_TOTAL_DEADLINE_MS, DEFAULT_MIN_BYTES_PER_SECOND);
    }

    public OpenHouseStandardLineProbe(long totalDeadlineMs, long minimumBytesPerSecond) {
        this.totalDeadlineMs = Math.max(1_000L, totalDeadlineMs);
        this.minimumBytesPerSecond = Math.max(1L, minimumBytesPerSecond);
        this.workerExecutor = Executors.newSingleThreadExecutor(
            namedThreadFactory("openhouse-standard-line-probe")
        );
    }

    public ProbeHandle start(Callback callback) {
        return start(null, callback);
    }

    public ProbeHandle start(Executor callbackExecutor, Callback callback) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Future<?> future = workerExecutor.submit(new Runnable() {
            @Override
            public void run() {
                Result result = runBlocking(cancelled);
                if (callback == null || cancelled.get()) {
                    return;
                }
                Runnable delivery = new Runnable() {
                    @Override
                    public void run() {
                        callback.onStandardLineProbeFinished(result);
                    }
                };
                if (callbackExecutor != null) {
                    callbackExecutor.execute(delivery);
                } else {
                    delivery.run();
                }
            }
        });
        return new ProbeHandle(cancelled, future);
    }

    public Result runBlocking() {
        return runBlocking(new AtomicBoolean(false));
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        workerExecutor.shutdownNow();
    }

    private Result runBlocking(AtomicBoolean cancelled) {
        long startedAtMs = nowMs();
        long deadlineAtMs = startedAtMs + totalDeadlineMs;
        ExecutorService categoryExecutor = Executors.newFixedThreadPool(
            CATEGORY_SPECS.size(),
            namedThreadFactory("openhouse-standard-line-category")
        );
        Map<Category, Future<CategoryResult>> futures = new EnumMap<>(Category.class);
        for (CategorySpec spec : CATEGORY_SPECS) {
            futures.put(spec.category, categoryExecutor.submit(() ->
                probeCategory(spec, deadlineAtMs, cancelled)
            ));
        }

        List<CategoryResult> categoryResults = new ArrayList<>();
        try {
            for (CategorySpec spec : CATEGORY_SPECS) {
                Future<CategoryResult> future = futures.get(spec.category);
                long remainingMs = deadlineAtMs - nowMs();
                if (cancelled.get()) {
                    cancelFutures(futures);
                    categoryResults.add(CategoryResult.cancelled(spec.category, elapsedMs(startedAtMs)));
                    continue;
                }
                if (remainingMs <= 0L) {
                    future.cancel(true);
                    categoryResults.add(CategoryResult.timedOut(spec.category, elapsedMs(startedAtMs)));
                    continue;
                }
                try {
                    categoryResults.add(future.get(remainingMs, TimeUnit.MILLISECONDS));
                } catch (TimeoutException e) {
                    future.cancel(true);
                    categoryResults.add(CategoryResult.timedOut(spec.category, elapsedMs(startedAtMs)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelled.set(true);
                    cancelFutures(futures);
                    categoryResults.add(CategoryResult.cancelled(spec.category, elapsedMs(startedAtMs)));
                } catch (CancellationException e) {
                    categoryResults.add(CategoryResult.cancelled(spec.category, elapsedMs(startedAtMs)));
                } catch (ExecutionException e) {
                    categoryResults.add(CategoryResult.failed(
                        spec.category,
                        "检测失败",
                        elapsedMs(startedAtMs),
                        Collections.<EndpointResult>emptyList()
                    ));
                }
            }
        } finally {
            cancelExpiredFutures(futures);
            categoryExecutor.shutdownNow();
        }

        boolean completedBeforeDeadline = nowMs() <= deadlineAtMs && !cancelled.get();
        Result result = new Result(
            categoryResults,
            elapsedMs(startedAtMs),
            completedBeforeDeadline,
            cancelled.get()
        );
        return result;
    }

    private CategoryResult probeCategory(CategorySpec spec,
                                         long deadlineAtMs,
                                         AtomicBoolean cancelled) {
        long startedAtMs = nowMs();
        List<EndpointResult> endpointResults = new ArrayList<>();
        for (EndpointSpec endpoint : spec.endpoints) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                return CategoryResult.cancelled(spec.category, elapsedMs(startedAtMs), endpointResults);
            }
            if (deadlineAtMs <= nowMs()) {
                return CategoryResult.timedOut(spec.category, elapsedMs(startedAtMs), endpointResults);
            }

            EndpointResult endpointResult = probeEndpoint(endpoint, deadlineAtMs, cancelled);
            endpointResults.add(endpointResult);
            if (endpointResult.isSuccessful()) {
                return CategoryResult.succeeded(
                    spec.category,
                    endpointResult.getBytesPerSecond(),
                    elapsedMs(startedAtMs),
                    endpointResults
                );
            }
        }

        boolean timedOut = false;
        boolean tooSlow = false;
        long bestBytesPerSecond = 0L;
        String detail = "检测未通过";
        for (EndpointResult endpointResult : endpointResults) {
            timedOut = timedOut || endpointResult.isTimedOut();
            tooSlow = tooSlow || endpointResult.isTooSlow();
            bestBytesPerSecond = Math.max(bestBytesPerSecond, endpointResult.getBytesPerSecond());
            if (endpointResult.getFailureReason() != null
                && !endpointResult.getFailureReason().isEmpty()) {
                detail = endpointResult.getFailureReason();
            }
        }
        return new CategoryResult(
            spec.category,
            false,
            timedOut,
            tooSlow,
            false,
            bestBytesPerSecond,
            elapsedMs(startedAtMs),
            detail,
            endpointResults
        );
    }

    private EndpointResult probeEndpoint(EndpointSpec endpoint,
                                         long deadlineAtMs,
                                         AtomicBoolean cancelled) {
        long startedAtMs = nowMs();
        HttpURLConnection connection = null;
        int responseCode = -1;
        long bytesRead = 0L;
        long bytesPerSecond = 0L;
        boolean timedOut = false;
        boolean tooSlow = false;
        String failureReason = "";

        try {
            long remainingMs = deadlineAtMs - nowMs();
            if (remainingMs <= 0L) {
                return EndpointResult.timedOut(endpoint.url, elapsedMs(startedAtMs));
            }

            URL url = new URL(endpoint.url);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout((int) Math.min(CONNECT_TIMEOUT_MS, remainingMs));
            connection.setReadTimeout((int) Math.min(READ_TIMEOUT_MS, remainingMs));
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Cache-Control", "no-cache");
            if (endpoint.readBody && endpoint.maxBytes > 0) {
                connection.setRequestProperty("Range", "bytes=0-" + (endpoint.maxBytes - 1));
            }
            connection.connect();
            responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 400) {
                return EndpointResult.failed(
                    endpoint.url,
                    responseCode,
                    elapsedMs(startedAtMs),
                    "连接失败"
                );
            }

            if (endpoint.readBody) {
                bytesRead = readBody(connection, endpoint.maxBytes, deadlineAtMs, cancelled);
                long elapsedMs = Math.max(1L, elapsedMs(startedAtMs));
                bytesPerSecond = (bytesRead * 1000L) / elapsedMs;
                if (endpoint.requiresThroughput
                    && bytesRead >= MIN_THROUGHPUT_SAMPLE_BYTES
                    && bytesPerSecond < minimumBytesPerSecond) {
                    tooSlow = true;
                    failureReason = "速度较慢";
                } else if (endpoint.requiresThroughput && bytesRead <= 0L) {
                    tooSlow = true;
                    failureReason = "没有收到有效数据";
                }
            }

            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                return EndpointResult.cancelled(
                    endpoint.url,
                    responseCode,
                    bytesRead,
                    bytesPerSecond,
                    elapsedMs(startedAtMs)
                );
            }
            if (deadlineAtMs <= nowMs()) {
                return EndpointResult.timedOut(
                    endpoint.url,
                    responseCode,
                    bytesRead,
                    bytesPerSecond,
                    elapsedMs(startedAtMs)
                );
            }
            if (tooSlow) {
                return EndpointResult.tooSlow(
                    endpoint.url,
                    responseCode,
                    bytesRead,
                    bytesPerSecond,
                    elapsedMs(startedAtMs),
                    failureReason
                );
            }
            return EndpointResult.succeeded(
                endpoint.url,
                responseCode,
                bytesRead,
                bytesPerSecond,
                elapsedMs(startedAtMs)
            );
        } catch (SocketTimeoutException e) {
            timedOut = true;
            failureReason = "连接超时";
        } catch (IOException e) {
            failureReason = "连接失败";
        } catch (RuntimeException e) {
            failureReason = "检测失败";
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        if (timedOut || deadlineAtMs <= nowMs()) {
            return EndpointResult.timedOut(
                endpoint.url,
                responseCode,
                bytesRead,
                bytesPerSecond,
                elapsedMs(startedAtMs)
            );
        }
        return EndpointResult.failed(
            endpoint.url,
            responseCode,
            bytesRead,
            bytesPerSecond,
            elapsedMs(startedAtMs),
            failureReason
        );
    }

    private long readBody(HttpURLConnection connection,
                          int maxBytes,
                          long deadlineAtMs,
                          AtomicBoolean cancelled) throws IOException {
        long totalRead = 0L;
        InputStream inputStream = connection.getInputStream();
        try {
            byte[] buffer = new byte[8 * 1024];
            while (totalRead < maxBytes) {
                if (cancelled.get() || Thread.currentThread().isInterrupted() || deadlineAtMs <= nowMs()) {
                    break;
                }
                int remaining = (int) Math.min(buffer.length, maxBytes - totalRead);
                int read = inputStream.read(buffer, 0, remaining);
                if (read < 0) {
                    break;
                }
                totalRead += read;
            }
        } finally {
            inputStream.close();
        }
        return totalRead;
    }

    private void cancelExpiredFutures(Map<Category, Future<CategoryResult>> futures) {
        for (Future<CategoryResult> future : futures.values()) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private void cancelFutures(Map<Category, Future<CategoryResult>> futures) {
        for (Future<CategoryResult> future : futures.values()) {
            future.cancel(true);
        }
    }

    private static List<CategorySpec> buildCategorySpecs() {
        List<CategorySpec> specs = new ArrayList<>();
        specs.add(new CategorySpec(
            Category.WEBPAGE_CONNECTIVITY,
            new EndpointSpec("https://www.google.com/generate_204", false, false),
            new EndpointSpec("https://www.gstatic.com/generate_204", false, false),
            new EndpointSpec("https://www.cloudflare.com/cdn-cgi/trace", true, false)
        ));
        specs.add(new CategorySpec(
            Category.CODE_DOWNLOAD_SOURCE,
            new EndpointSpec("https://github.com/", true, true),
            new EndpointSpec("https://raw.githubusercontent.com/", true, true)
        ));
        specs.add(new CategorySpec(
            Category.AI_TOOL_SOURCE,
            new EndpointSpec("https://registry.npmjs.org/npm", true, true),
            new EndpointSpec("https://registry.npmjs.org/-/ping", true, false)
        ));
        specs.add(new CategorySpec(
            Category.SYSTEM_COMPONENT_SOURCE,
            new EndpointSpec("https://packages.termux.dev/apt/termux-main/dists/stable/Release", true, true),
            new EndpointSpec("https://packages-cf.termux.dev/apt/termux-main/dists/stable/Release", true, true),
            new EndpointSpec("https://archive.ubuntu.com/ubuntu/dists/noble/Release", true, true)
        ));
        specs.add(new CategorySpec(
            Category.OPENHOUSE_DOWNLOAD_SOURCE,
            new EndpointSpec("https://raw.githubusercontent.com/jiwuyou/openhouseai-bootstrap/main/bootstrap.sh", true, true),
            new EndpointSpec("https://github.com/jiwuyou/openhouseai-bootstrap", true, true)
        ));
        return Collections.unmodifiableList(specs);
    }

    private static ThreadFactory namedThreadFactory(String namePrefix) {
        AtomicBoolean firstThread = new AtomicBoolean(true);
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                String name = firstThread.getAndSet(false) ? namePrefix : namePrefix + "-" + nowMs();
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private static long nowMs() {
        return System.currentTimeMillis();
    }

    private static long elapsedMs(long startedAtMs) {
        return Math.max(0L, nowMs() - startedAtMs);
    }

    public interface Callback {
        void onStandardLineProbeFinished(Result result);
    }

    public enum Category {
        WEBPAGE_CONNECTIVITY("webpage_connectivity", "网页连通性"),
        CODE_DOWNLOAD_SOURCE("code_download_source", "代码下载源"),
        AI_TOOL_SOURCE("ai_tool_source", "AI 工具源"),
        SYSTEM_COMPONENT_SOURCE("system_component_source", "系统组件源"),
        OPENHOUSE_DOWNLOAD_SOURCE("openhouse_download_source", "OpenHouse 下载源");

        private final String key;
        private final String label;

        Category(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String getKey() {
            return key;
        }

        public String getLabel() {
            return label;
        }
    }

    public static final class ProbeHandle {
        private final AtomicBoolean cancelled;
        private final Future<?> future;

        private ProbeHandle(AtomicBoolean cancelled, Future<?> future) {
            this.cancelled = cancelled;
            this.future = future;
        }

        public boolean cancel() {
            cancelled.set(true);
            return future.cancel(true);
        }

        public boolean isCancelled() {
            return cancelled.get() || future.isCancelled();
        }

        public boolean isDone() {
            return future.isDone();
        }
    }

    public static final class Result {
        private final List<CategoryResult> categoryResults;
        private final long elapsedMs;
        private final boolean completedBeforeDeadline;
        private final boolean cancelled;
        private final boolean recommended;

        private Result(List<CategoryResult> categoryResults,
                       long elapsedMs,
                       boolean completedBeforeDeadline,
                       boolean cancelled) {
            List<CategoryResult> safeResults = categoryResults == null
                ? Collections.<CategoryResult>emptyList()
                : new ArrayList<>(categoryResults);
            this.categoryResults = Collections.unmodifiableList(safeResults);
            this.elapsedMs = Math.max(0L, elapsedMs);
            this.completedBeforeDeadline = completedBeforeDeadline;
            this.cancelled = cancelled;
            this.recommended = computeRecommended(safeResults, completedBeforeDeadline, cancelled);
        }

        public List<CategoryResult> getCategoryResults() {
            return categoryResults;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public boolean completedBeforeDeadline() {
            return completedBeforeDeadline;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public boolean isRecommended() {
            return recommended;
        }

        public boolean requiresSecondConfirmation() {
            return !recommended;
        }

        public String getTitle() {
            return recommended ? "标准线路可用" : "标准线路检测不稳定";
        }

        public String getMessage() {
            if (recommended) {
                return "检测通过，可以使用标准线路安装。";
            }
            return "检测到部分下载来源连接失败或速度较慢。继续切换可能导致安装中断。";
        }

        private static boolean computeRecommended(List<CategoryResult> categoryResults,
                                                  boolean completedBeforeDeadline,
                                                  boolean cancelled) {
            if (cancelled || !completedBeforeDeadline || categoryResults.size() != CATEGORY_SPECS.size()) {
                return false;
            }
            for (CategoryResult categoryResult : categoryResults) {
                if (!categoryResult.isSuccessful()
                    || categoryResult.isTimedOut()
                    || categoryResult.isTooSlow()) {
                    return false;
                }
            }
            return true;
        }
    }

    public static final class CategoryResult {
        private final Category category;
        private final boolean successful;
        private final boolean timedOut;
        private final boolean tooSlow;
        private final boolean cancelled;
        private final long bestBytesPerSecond;
        private final long elapsedMs;
        private final String detail;
        private final List<EndpointResult> endpointResults;

        private CategoryResult(Category category,
                               boolean successful,
                               boolean timedOut,
                               boolean tooSlow,
                               boolean cancelled,
                               long bestBytesPerSecond,
                               long elapsedMs,
                               String detail,
                               List<EndpointResult> endpointResults) {
            this.category = category;
            this.successful = successful;
            this.timedOut = timedOut;
            this.tooSlow = tooSlow;
            this.cancelled = cancelled;
            this.bestBytesPerSecond = Math.max(0L, bestBytesPerSecond);
            this.elapsedMs = Math.max(0L, elapsedMs);
            this.detail = detail == null ? "" : detail;
            this.endpointResults = Collections.unmodifiableList(
                endpointResults == null
                    ? Collections.<EndpointResult>emptyList()
                    : new ArrayList<>(endpointResults)
            );
        }

        private static CategoryResult succeeded(Category category,
                                                long bestBytesPerSecond,
                                                long elapsedMs,
                                                List<EndpointResult> endpointResults) {
            return new CategoryResult(
                category,
                true,
                false,
                false,
                false,
                bestBytesPerSecond,
                elapsedMs,
                "检测通过",
                endpointResults
            );
        }

        private static CategoryResult failed(Category category,
                                             String detail,
                                             long elapsedMs,
                                             List<EndpointResult> endpointResults) {
            return new CategoryResult(
                category,
                false,
                false,
                false,
                false,
                0L,
                elapsedMs,
                detail,
                endpointResults
            );
        }

        private static CategoryResult timedOut(Category category, long elapsedMs) {
            return timedOut(category, elapsedMs, Collections.<EndpointResult>emptyList());
        }

        private static CategoryResult timedOut(Category category,
                                               long elapsedMs,
                                               List<EndpointResult> endpointResults) {
            return new CategoryResult(
                category,
                false,
                true,
                false,
                false,
                bestBytesPerSecond(endpointResults),
                elapsedMs,
                "检测超时",
                endpointResults
            );
        }

        private static CategoryResult cancelled(Category category, long elapsedMs) {
            return cancelled(category, elapsedMs, Collections.<EndpointResult>emptyList());
        }

        private static CategoryResult cancelled(Category category,
                                                long elapsedMs,
                                                List<EndpointResult> endpointResults) {
            return new CategoryResult(
                category,
                false,
                false,
                false,
                true,
                bestBytesPerSecond(endpointResults),
                elapsedMs,
                "检测已取消",
                endpointResults
            );
        }

        public Category getCategory() {
            return category;
        }

        public String getKey() {
            return category.getKey();
        }

        public String getLabel() {
            return category.getLabel();
        }

        public boolean isSuccessful() {
            return successful;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public boolean isTooSlow() {
            return tooSlow;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public long getBestBytesPerSecond() {
            return bestBytesPerSecond;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public String getDetail() {
            return detail;
        }

        public List<EndpointResult> getEndpointResults() {
            return endpointResults;
        }

        public String getStatusLabel() {
            if (successful) {
                return "可用";
            }
            if (cancelled) {
                return "已取消";
            }
            if (timedOut) {
                return "超时";
            }
            if (tooSlow) {
                return "较慢";
            }
            return "不可用";
        }

        private static long bestBytesPerSecond(List<EndpointResult> endpointResults) {
            long best = 0L;
            if (endpointResults == null) {
                return best;
            }
            for (EndpointResult endpointResult : endpointResults) {
                best = Math.max(best, endpointResult.getBytesPerSecond());
            }
            return best;
        }
    }

    public static final class EndpointResult {
        private final String url;
        private final int responseCode;
        private final boolean successful;
        private final boolean timedOut;
        private final boolean tooSlow;
        private final boolean cancelled;
        private final long bytesRead;
        private final long bytesPerSecond;
        private final long elapsedMs;
        private final String failureReason;

        private EndpointResult(String url,
                               int responseCode,
                               boolean successful,
                               boolean timedOut,
                               boolean tooSlow,
                               boolean cancelled,
                               long bytesRead,
                               long bytesPerSecond,
                               long elapsedMs,
                               String failureReason) {
            this.url = url == null ? "" : url;
            this.responseCode = responseCode;
            this.successful = successful;
            this.timedOut = timedOut;
            this.tooSlow = tooSlow;
            this.cancelled = cancelled;
            this.bytesRead = Math.max(0L, bytesRead);
            this.bytesPerSecond = Math.max(0L, bytesPerSecond);
            this.elapsedMs = Math.max(0L, elapsedMs);
            this.failureReason = failureReason == null ? "" : failureReason;
        }

        private static EndpointResult succeeded(String url,
                                                int responseCode,
                                                long bytesRead,
                                                long bytesPerSecond,
                                                long elapsedMs) {
            return new EndpointResult(
                url,
                responseCode,
                true,
                false,
                false,
                false,
                bytesRead,
                bytesPerSecond,
                elapsedMs,
                ""
            );
        }

        private static EndpointResult failed(String url,
                                             int responseCode,
                                             long elapsedMs,
                                             String failureReason) {
            return failed(url, responseCode, 0L, 0L, elapsedMs, failureReason);
        }

        private static EndpointResult failed(String url,
                                             int responseCode,
                                             long bytesRead,
                                             long bytesPerSecond,
                                             long elapsedMs,
                                             String failureReason) {
            return new EndpointResult(
                url,
                responseCode,
                false,
                false,
                false,
                false,
                bytesRead,
                bytesPerSecond,
                elapsedMs,
                failureReason
            );
        }

        private static EndpointResult timedOut(String url, long elapsedMs) {
            return timedOut(url, -1, 0L, 0L, elapsedMs);
        }

        private static EndpointResult timedOut(String url,
                                               int responseCode,
                                               long bytesRead,
                                               long bytesPerSecond,
                                               long elapsedMs) {
            return new EndpointResult(
                url,
                responseCode,
                false,
                true,
                false,
                false,
                bytesRead,
                bytesPerSecond,
                elapsedMs,
                "连接超时"
            );
        }

        private static EndpointResult tooSlow(String url,
                                              int responseCode,
                                              long bytesRead,
                                              long bytesPerSecond,
                                              long elapsedMs,
                                              String failureReason) {
            return new EndpointResult(
                url,
                responseCode,
                false,
                false,
                true,
                false,
                bytesRead,
                bytesPerSecond,
                elapsedMs,
                failureReason
            );
        }

        private static EndpointResult cancelled(String url,
                                                int responseCode,
                                                long bytesRead,
                                                long bytesPerSecond,
                                                long elapsedMs) {
            return new EndpointResult(
                url,
                responseCode,
                false,
                false,
                false,
                true,
                bytesRead,
                bytesPerSecond,
                elapsedMs,
                "检测已取消"
            );
        }

        public String getUrl() {
            return url;
        }

        public int getResponseCode() {
            return responseCode;
        }

        public boolean isSuccessful() {
            return successful;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public boolean isTooSlow() {
            return tooSlow;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public long getBytesRead() {
            return bytesRead;
        }

        public long getBytesPerSecond() {
            return bytesPerSecond;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public String getFailureReason() {
            return failureReason;
        }
    }

    private static final class CategorySpec {
        final Category category;
        final List<EndpointSpec> endpoints;

        CategorySpec(Category category, EndpointSpec... endpoints) {
            this.category = category;
            List<EndpointSpec> endpointList = new ArrayList<>();
            if (endpoints != null) {
                Collections.addAll(endpointList, endpoints);
            }
            this.endpoints = Collections.unmodifiableList(endpointList);
        }
    }

    private static final class EndpointSpec {
        final String url;
        final boolean readBody;
        final boolean requiresThroughput;
        final int maxBytes;

        EndpointSpec(String url, boolean readBody, boolean requiresThroughput) {
            this.url = url;
            this.readBody = readBody;
            this.requiresThroughput = requiresThroughput;
            this.maxBytes = MAX_PROBE_BYTES;
        }
    }
}
