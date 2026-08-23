package com.ignite.infra;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 纯单元测试：验证执行结果错误判定启发式。
 * 关键回归场景：正常返回值中包含 error/failed 字样（如 errorMsg 字段）不应误判为错误。
 */
public class IgniteConsoleServiceErrorDetectionTest {

    @Test
    public void nullOrBlankIsNotError() {
        assertFalse(IgniteConsoleService.isErrorResult(null));
        assertFalse(IgniteConsoleService.isErrorResult(""));
        assertFalse(IgniteConsoleService.isErrorResult("   "));
    }

    @Test
    public void exceptionClassNamesAreErrors() {
        assertTrue(IgniteConsoleService.isErrorResult("java.lang.NullPointerException: name"));
        assertTrue(IgniteConsoleService.isErrorResult("com.example.BusinessException: 库存不足"));
        assertTrue(IgniteConsoleService.isErrorResult("IllegalStateException"));
    }

    @Test
    public void causedByLinesAreErrors() {
        assertTrue(IgniteConsoleService.isErrorResult(
            "java.lang.IllegalStateException: x\nCaused by: java.io.IOException: io"));
    }

    @Test
    public void pluginFailureMarkersAreErrors() {
        assertTrue(IgniteConsoleService.isErrorResult("[Ignite] 未找到 Bean 实例"));
        assertTrue(IgniteConsoleService.isErrorResult("通信失败: Connection refused"));
        assertTrue(IgniteConsoleService.isErrorResult(
            "\n[Ignite] 等待提示符超时，以上为部分输出（方法可能仍在执行或被断点暂停）"));
    }

    @Test
    public void normalResultsContainingErrorWordsAreNotFlagged() {
        // 旧版启发式会把下面这些正常结果标红（contains("error")）
        assertFalse(IgniteConsoleService.isErrorResult("{\"errorCode\":0,\"errorMsg\":null}"));
        assertFalse(IgniteConsoleService.isErrorResult("{\"data\":[],\"failedCount\":0}"));
        assertFalse(IgniteConsoleService.isErrorResult("no error, all good"));
        assertFalse(IgniteConsoleService.isErrorResult("{\"status\":\"ok\",\"errorHandlers\":3}"));
    }
}
