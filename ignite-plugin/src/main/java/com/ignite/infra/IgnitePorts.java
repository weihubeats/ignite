package com.ignite.infra;

/**
 * 按 PID 确定性分配 Arthas telnet 端口。
 * 旧版本固定使用 3658，导致同时调试多个服务时端口冲突（第二个 agent 启动失败）。
 */
final class IgnitePorts {

    /** 兼容旧版插件的默认探测端口 */
    static final int BASE_PORT = 3658;
    private static final int PORT_RANGE = 200;

    private IgnitePorts() {}

    /**
     * 同一 PID 永远得到同一端口；不同 PID 以高概率得到不同端口（3659 ~ 3658+RANGE-1）。
     * BASE_PORT 本身不参与分配，留给旧 agent / 无 PID 探测场景。
     */
    static int portFor(String pid) {
        if (pid == null || pid.isBlank()) {
            return BASE_PORT;
        }
        int offset = Math.floorMod(pid.hashCode(), PORT_RANGE - 1) + 1;
        return BASE_PORT + offset;
    }
}
