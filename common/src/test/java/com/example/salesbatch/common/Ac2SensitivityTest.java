package com.example.salesbatch.common;

import org.junit.Assert;
import org.junit.Test;

// Card SBM-2, AC2 sensitivity: never merged. The java job must be red.
public class Ac2SensitivityTest {
    @Test
    public void ac2SensitivityAlwaysFails() {
        Assert.fail("AC2 sensitivity: deliberate failure");
    }
}
