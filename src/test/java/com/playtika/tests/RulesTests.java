package com.playtika.tests;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

import java.io.File;
import java.io.IOException;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;

public class RulesTests {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Rule
    public final TestName name = new TestName();

    @Test
    public void testTemporaryFolderRule() throws IOException {
        File newFolder = tempFolder.newFolder("Temp Folder");
        assertTrue(newFolder.exists());
    }

    @Test
    public void testTestNameRule() {
        assertEquals(name.getMethodName(), "testTestNameRules");
    }

    @Test
    public void testTwo() {
        assertEquals(true, true);
    }
}