package fr.an.llms.rest;

import java.util.Collection;
import java.util.function.Function;

import org.slf4j.Logger;

/**
 * Base class for REST controllers, providing {@link #withLog} to run an endpoint's action while
 * logging the call before and after (with elapsed time), consistently prefixed with the
 * controller's own {@link #baseUrl}. On failure, the error is logged (with elapsed time) and
 * rethrown wrapped as a {@link RuntimeException}, unless it already is one.
 *
 * <p>Callers pass their own (subclass) {@code @Slf4j}-generated {@code log}, so log lines are
 * correctly attributed to the concrete controller rather than to this base class.
 */
public abstract class AbstractRestController {

    /** Elapsed time (in milliseconds) above which the "done" line is logged even without a result message. */
    private static final long SLOW_CALL_MILLIS = 300;

    /** A {@link java.util.function.Supplier}-like callback allowed to throw a checked exception. */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** A {@link Runnable}-like callback allowed to throw a checked exception. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    private final String baseUrl;

    protected AbstractRestController(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Runs {@code action}, logging the endpoint call at debug level before, and after (with elapsed time). */
    protected <T> T withLogDebug(Logger log, String method, String path, String paramsText, ThrowingSupplier<T> action) {
        return doWithLog(log, false, method, path, paramsText, action, null);
    }

    /** Runs {@code action}, logging the endpoint call at debug level before, and after (with elapsed time
     * and the message extracted from the result by {@code extractDisplayMessageFromResultFunction}). */
    protected <T> T withLogDebug(Logger log, String method, String path, String paramsText, ThrowingSupplier<T> action,
            Function<T,String> extractDisplayMessageFromResultFunction) {
        return doWithLog(log, false, method, path, paramsText, action, extractDisplayMessageFromResultFunction);
    }

    /** Runs {@code action}, logging the endpoint call before and after (with elapsed time). */
    protected <T> T withLog(Logger log, String method, String path, String paramsText, ThrowingSupplier<T> action) {
        return doWithLog(log, true, method, path, paramsText, action, null);
    }

    /** Runs {@code action}, logging the endpoint call before and after (with elapsed time and the message
     * extracted from the result by {@code extractDisplayMessageFromResultFunction}, for example the count of
     * returned elements). */
    protected <T> T withLog(Logger log, String method, String path, String paramsText, ThrowingSupplier<T> action,
            Function<T,String> extractDisplayMessageFromResultFunction) {
        return doWithLog(log, true, method, path, paramsText, action, extractDisplayMessageFromResultFunction);
    }

    /** Only the level of the "call started" line differs between {@link #withLog} and {@link #withLogDebug};
     * the "done" (when slow, or when a result message is extracted) and "Failed" lines are always logged at
     * info/error level.
     * @param extractDisplayMessageFromResultFunction optional (may be null), called on the action result to
     *   append a short display message to the "done" line, which is then always logged.
     */
    private <T> T doWithLog(Logger log, boolean infoLevel, String method, String path, String paramsText, ThrowingSupplier<T> action,
            Function<T,String> extractDisplayMessageFromResultFunction) {
        String endpointUrl = baseUrl + path;
        String endpointCallMsg = "http " + method + " " + endpointUrl + ((paramsText != null && !paramsText.isBlank()) ? " " + paramsText : "");
        if (infoLevel) {
            log.info(endpointCallMsg);
        } else {
            log.debug(endpointCallMsg);
        }
        long startTime = System.currentTimeMillis();
        try {
            T result = action.get();

            long millis = System.currentTimeMillis() - startTime;
            String resultMsg = (extractDisplayMessageFromResultFunction != null) ? extractDisplayMessageFromResultFunction.apply(result) : null;
            if (extractDisplayMessageFromResultFunction == null && result instanceof Collection resultColl) {
                resultMsg = resultColl.size() + " items";
            }

            String logResultMsg =  "... done " + endpointCallMsg
                    + ((resultMsg != null && !resultMsg.isBlank())? " -> " + resultMsg : "")
                    + " (took " + millis + " ms)";
            if (infoLevel || millis > 500) {
                log.info(logResultMsg);
            } else {
                log.debug(logResultMsg);
            }

            return result;
        } catch (Exception ex) {
            long millis = System.currentTimeMillis() - startTime;
            log.error("... Failed " + endpointCallMsg + " (took " + millis + " ms), rethrowing " + ex.getMessage());
            throw (ex instanceof RuntimeException re) ? re : new RuntimeException(ex);
        }
    }

    /** Void-returning variant of {@link #withLog(Logger, String, String, String, ThrowingSupplier)}. */
    protected void withLog(Logger log, String method, String path, String paramsText, ThrowingRunnable action) {
        withLog(log, method, path, paramsText, () -> {
            action.run();
            return null;
        });
    }

}
