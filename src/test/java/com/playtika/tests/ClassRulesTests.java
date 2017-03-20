package com.playtika.tests;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertTrue;

public class ClassRulesTests {
    private static final Logger logger = LoggerFactory.getLogger(ClassRulesTests.class);

    @ClassRule
    public final static ExternalResource resource = new ExternalResource() {
        @Override
        protected void before() throws Throwable {
            logger.info("Class rule before.");
        };

        @Override
        protected void after() {
            logger.info("Class rule after.");
        };
    };

    @Before
    public void setUp() {
        logger.info("Test setUp");
    }

    @After
    public void tearDown() {
        logger.info("Test tearDown");
    }

    @Test
    public void testOne() throws Exception {
        assertTrue(true);
    }

    @Test
    public void testTwo() throws Exception {
        assertTrue(false);
    }
}