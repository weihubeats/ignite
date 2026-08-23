package com.ignite.infra;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class IgnitePortsTest {

    @Test
    public void samePidAlwaysMapsToSamePort() {
        for (int i = 0; i < 100; i++) {
            String pid = String.valueOf(1000 + i);
            assertEquals(IgnitePorts.portFor(pid), IgnitePorts.portFor(pid));
        }
    }

    @Test
    public void portWithinAllocatableRange() {
        for (int i = 0; i < 500; i++) {
            int port = IgnitePorts.portFor(String.valueOf(i));
            assertTrue("port out of range: " + port,
                port > IgnitePorts.BASE_PORT && port < IgnitePorts.BASE_PORT + 200);
        }
    }

    @Test
    public void blankOrNullPidFallsBackToBasePort() {
        assertEquals(IgnitePorts.BASE_PORT, IgnitePorts.portFor(null));
        assertEquals(IgnitePorts.BASE_PORT, IgnitePorts.portFor(""));
        assertEquals(IgnitePorts.BASE_PORT, IgnitePorts.portFor("  "));
    }

    @Test
    public void distinctPidsSpreadAcrossPorts() {
        Set<Integer> ports = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            ports.add(IgnitePorts.portFor(String.valueOf(30000 + i)));
        }
        // 40 个不同 PID 至少应分布到 20 个不同端口，避免同时调试多服务时端口冲突
        assertTrue("ports spread too narrow: " + ports.size(), ports.size() >= 20);
    }
}
