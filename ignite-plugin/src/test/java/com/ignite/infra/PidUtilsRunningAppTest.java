package com.ignite.infra;

import static org.junit.Assert.assertEquals;

import com.ignite.infra.PidUtils.RunningApp;
import org.junit.Test;

public class PidUtilsRunningAppTest {

    @Test
    public void stripsRunProfilePrefix() {
        RunningApp app = new RunningApp("123", "Run profile 'UserServiceApplication'");
        assertEquals("UserService (123)", app.displayName);
    }

    @Test
    public void stripsPackagePrefix() {
        RunningApp app = new RunningApp("123", "com.example.shop.OrderService");
        assertEquals("OrderService (123)", app.displayName);
    }

    @Test
    public void stripsApplicationSuffixForLongNames() {
        RunningApp app = new RunningApp("123", "InventoryServiceApplication");
        assertEquals("InventoryService (123)", app.displayName);
    }

    @Test
    public void keepsShortApplicationNameUntouched() {
        // 短名字去掉 Application 后缀会变得不可读，应保持原样
        RunningApp app = new RunningApp("123", "App");
        assertEquals("App (123)", app.displayName);
    }

    @Test
    public void nullRawNameFallsBackToUnknown() {
        RunningApp app = new RunningApp("123", null);
        assertEquals("Unknown (123)", app.displayName);
    }
}
