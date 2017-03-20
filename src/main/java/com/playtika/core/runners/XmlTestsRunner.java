package com.playtika.core.runners;

import com.playtika.core.XmlTestsLoader;
import org.junit.*;
import org.junit.internal.runners.model.EachTestNotifier;
import org.junit.internal.runners.model.ReflectiveCallable;
import org.junit.internal.runners.statements.*;
import org.junit.rules.MethodRule;
import org.junit.rules.RunRules;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.notification.RunNotifier;
import org.junit.runner.notification.StoppedByUserException;
import org.junit.runners.model.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class XmlTestsRunner extends Runner {
    private final ConcurrentHashMap<FrameworkMethod, Description> methodDescriptions = new ConcurrentHashMap<>();
    private final HashMap<Class<?>, TestClass> testClasses = new HashMap<>();
    private final HashMap<Class<?>, List<FrameworkMethod>> testsToBeExecuted = new HashMap<>();
    private final Object childrenLock = new Object();
    private final Map<Class<?>, Collection<String>> xmlTests;
    private final TestClass testClass;

    // Guarded by childrenLock
    private volatile ConcurrentHashMap<Class<?>, Collection<FrameworkMethod>> filteredChildren = new ConcurrentHashMap<>();

    private volatile RunnerScheduler scheduler = new RunnerScheduler() {
        @Override
        public void schedule(Runnable childStatement) {
            childStatement.run();
        }

        @Override
        public void finished() {
            // do nothing
        }
    };

    public XmlTestsRunner(Class<?> clazz) throws InitializationError {
        this.testClass = createTestClass(clazz);
        this.xmlTests = XmlTestsLoader.getInstance().getTests();
        populateTestClasses();
        populateTestsToBeExecuted();
    }

    private void populateTestClasses() {
        xmlTests.forEach((clazz, tests) -> testClasses.put(clazz, createTestClass(clazz)));
    }

    private TestClass createTestClass(Class<?> testClass) {
        return new TestClass(testClass);
    }

    private void populateTestsToBeExecuted() {
        xmlTests.forEach((clazz, tests) -> {
            List<FrameworkMethod> methodsToRun = new ArrayList<>();
            testClasses.get(clazz).getAnnotatedMethods(Test.class).forEach(t -> {
                if (tests.stream().anyMatch(test -> test.equals(t.getName()))) {
                    methodsToRun.add(t);
                }
            });
            testsToBeExecuted.put(clazz, methodsToRun);
        });
    }

    private String getName(Class<?> clazz) {
        return clazz.getName();
    }

    //
    // Implementation of Runner
    //
    @Override
    public Description getDescription() {
        Description desc = Description.createSuiteDescription(getName(testClass.getJavaClass()),
                getRunnerAnnotations(testClass.getJavaClass()));
        for (Class<?> clazz : xmlTests.keySet()) {
            desc.addChild(getDescription(clazz));
        }
        return desc;
    }

    @Override
    public void run(final RunNotifier notifier) {
        xmlTests.keySet().forEach(clazz -> {
            EachTestNotifier testNotifier = new EachTestNotifier(notifier, getDescription(clazz));
            try {
                Statement statement = classBlock(notifier, clazz);
                statement.evaluate();
            } catch (org.junit.internal.AssumptionViolatedException e) {
                testNotifier.addFailedAssumption(e);
            } catch (StoppedByUserException e) {
                throw e;
            } catch (Throwable e) {
                testNotifier.addFailure(e);
            }
        });
    }

    private Description getDescription(Class<?> clazz) {
        Description description = Description.createSuiteDescription(getName(clazz), getRunnerAnnotations(clazz));
        getFilteredChildren(clazz).forEach(child -> description.addChild(describeChild(clazz, child)));
        return description;
    }

    private Description describeChild(final Class<?> clazz, FrameworkMethod method) {
        Description description = methodDescriptions.get(method);

        if (description == null) {
            description = Description.createTestDescription(clazz, testName(method), method.getAnnotations());
            methodDescriptions.putIfAbsent(method, description);
        }

        return description;
    }

    /**
     * @return the annotations that should be attached to this runner's description.
     */
    private Annotation[] getRunnerAnnotations(Class<?> clazz) {
        return clazz.getAnnotations();
    }

    private Collection<FrameworkMethod> getFilteredChildren(Class<?> clazz) {
        if (!filteredChildren.containsKey(clazz)) {
            synchronized (childrenLock) {
                if (!filteredChildren.containsKey(clazz)) {
                    filteredChildren.put(clazz, Collections.unmodifiableCollection(testsToBeExecuted.get(clazz)));
                }
            }
        }
        return filteredChildren.get(clazz);
    }

    private void runChild(final Class<?> clazz, final FrameworkMethod method, RunNotifier notifier) {
        Description description = describeChild(clazz, method);
        if (isIgnored(method)) {
            notifier.fireTestIgnored(description);
        } else {
            runLeaf(methodBlock(clazz, method), description, notifier);
        }
    }

    /**
     * Runs a {@link Statement} that represents a leaf (aka atomic) test.
     */
    private void runLeaf(Statement statement, Description description,
                         RunNotifier notifier) {
        EachTestNotifier eachNotifier = new EachTestNotifier(notifier, description);
        eachNotifier.fireTestStarted();
        try {
            statement.evaluate();
        } catch (org.junit.internal.AssumptionViolatedException e) {
            eachNotifier.addFailedAssumption(e);
        } catch (Throwable e) {
            eachNotifier.addFailure(e);
        } finally {
            eachNotifier.fireTestFinished();
        }
    }

    private Statement classBlock(final RunNotifier notifier, Class<?> clazz) {
        Statement statement = childrenInvoker(notifier, clazz);
        if (!areAllChildrenIgnored(clazz)) {
            statement = withBeforeClasses(statement, clazz);
            statement = withAfterClasses(statement, clazz);
            statement = withClassRules(statement, clazz);
        }
        return statement;
    }

    private boolean areAllChildrenIgnored(Class<?> clazz) {
        for (FrameworkMethod child : getFilteredChildren(clazz)) {
            if (!isIgnored(child)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the {@code ClassRule}s that can transform the block that runs
     * each method in the tested class.
     */
    private List<TestRule> classRules(Class<?> clazz) {
        List<TestRule> result = testClasses.get(clazz).getAnnotatedMethodValues(null, ClassRule.class, TestRule.class);
        result.addAll(testClasses.get(clazz).getAnnotatedFieldValues(null, ClassRule.class, TestRule.class));
        return result;
    }

    private Statement childrenInvoker(final RunNotifier notifier, final Class<?> clazz) {
        return new Statement() {
            @Override
            public void evaluate() {
                runChildren(notifier, clazz);
            }
        };
    }

    private boolean isIgnored(FrameworkMethod child) {
        return child.getAnnotation(Ignore.class) != null;
    }

    private void runChildren(final RunNotifier notifier, final Class<?> clazz) {
        final RunnerScheduler currentScheduler = scheduler;
        try {
            for (final FrameworkMethod each : getFilteredChildren(clazz)) {
                currentScheduler.schedule(() -> runChild(clazz, each, notifier));
            }
        } finally {
            currentScheduler.finished();
        }
    }

    /**
     * Returns a Statement that, when executed, either returns normally if
     * {@code method} passes, or throws an exception if {@code method} fails.
     */
    private Statement methodBlock(final Class<?> clazz, FrameworkMethod method) {
        Object test;
        try {
            test = new ReflectiveCallable() {
                @Override
                protected Object runReflectiveCall() throws Throwable {
                    return createTest(clazz);
                }
            }.run();
        } catch (Throwable e) {
            return new Fail(e);
        }
        Statement statement = methodInvoker(method, test);
        statement = possiblyExpectingExceptions(method, statement);
        statement = withPotentialTimeout(method, statement);
        statement = withBefores(clazz, test, statement);
        statement = withAfters(clazz, test, statement);
        statement = withRules(clazz, method, test, statement);
        return statement;
    }

    private String testName(FrameworkMethod method) {
        return method.getName();
    }

    /**
     * Returns a new fixture for running a test.
     */
    private Object createTest(Class<?> clazz)
            throws IllegalAccessException, InvocationTargetException, InstantiationException {
        return testClasses.get(clazz).getOnlyConstructor().newInstance();
    }

    //
    // Statement builders
    //
    private Statement withBeforeClasses(Statement statement, Class<?> clazz) {
        List<FrameworkMethod> befores = testClasses.get(clazz).getAnnotatedMethods(BeforeClass.class);
        return befores.isEmpty() ? statement :
                new RunBefores(statement, befores, null);
    }

    private Statement withAfterClasses(Statement statement, Class<?> clazz) {
        List<FrameworkMethod> afters = testClasses.get(clazz).getAnnotatedMethods(AfterClass.class);
        return afters.isEmpty() ? statement : new RunAfters(statement, afters, null);
    }

    private Statement withClassRules(Statement statement, Class<?> clazz) {
        List<TestRule> classRules = classRules(clazz);
        return classRules.isEmpty() ? statement :
                new RunRules(statement, classRules, getDescription(clazz));
    }

    private Statement methodInvoker(FrameworkMethod method, Object test) {
        return new InvokeMethod(method, test);
    }

    private Statement possiblyExpectingExceptions(FrameworkMethod method, Statement next) {
        Test annotation = method.getAnnotation(Test.class);
        return expectsException(annotation) ? new ExpectException(next,
                getExpectedException(annotation)) : next;
    }

    /**
     * @deprecated
     */
    @Deprecated
    private Statement withPotentialTimeout(FrameworkMethod method, Statement next) {
        long timeout = getTimeout(method.getAnnotation(Test.class));
        if (timeout <= 0) {
            return next;
        }
        return FailOnTimeout.builder()
                .withTimeout(timeout, TimeUnit.MILLISECONDS)
                .build(next);
    }

    private Statement withBefores(Class<?> clazz, Object target, Statement statement) {
        List<FrameworkMethod> befores = testClasses.get(clazz).getAnnotatedMethods(Before.class);
        return befores.isEmpty() ? statement : new RunBefores(statement, befores, target);
    }

    private Statement withAfters(Class<?> clazz, Object target, Statement statement) {
        List<FrameworkMethod> afters = testClasses.get(clazz).getAnnotatedMethods(After.class);
        return afters.isEmpty() ? statement : new RunAfters(statement, afters, target);
    }

    private Statement withRules(Class<?> clazz, FrameworkMethod method, Object target, Statement statement) {
        List<TestRule> testRules = getTestRules(clazz, target);
        Statement result = statement;
        result = withMethodRules(clazz, method, testRules, target, result);
        result = withTestRules(clazz, method, testRules, result);
        return result;
    }

    private Statement withMethodRules(Class<?> clazz, FrameworkMethod method, List<TestRule> testRules,
                                      Object target, Statement statement) {
        Statement result = statement;
        for (org.junit.rules.MethodRule each : getMethodRules(clazz, target)) {
            if (!testRules.contains(each)) {
                result = each.apply(result, method, target);
            }
        }
        return result;
    }

    private List<org.junit.rules.MethodRule> getMethodRules(Class<?> clazz, Object target) {
        return rules(clazz, target);
    }

    private List<MethodRule> rules(Class<?> clazz, Object target) {
        List<MethodRule> rules = testClasses.get(clazz).getAnnotatedMethodValues(target, Rule.class, MethodRule.class);
        rules.addAll(testClasses.get(clazz).getAnnotatedFieldValues(target, Rule.class, MethodRule.class));
        return rules;
    }

    private Statement withTestRules(Class<?> clazz, FrameworkMethod method,
                                    List<TestRule> testRules, Statement statement) {
        return testRules.isEmpty() ? statement : new RunRules(statement, testRules, describeChild(clazz, method));
    }

    private List<TestRule> getTestRules(Class<?> clazz, Object target) {
        List<TestRule> result = testClasses.get(clazz).getAnnotatedMethodValues(target, Rule.class, TestRule.class);
        result.addAll(testClasses.get(clazz).getAnnotatedFieldValues(target, Rule.class, TestRule.class));
        return result;
    }

    private Class<? extends Throwable> getExpectedException(Test annotation) {
        if (annotation == null || annotation.expected() == Test.None.class) {
            return null;
        } else {
            return annotation.expected();
        }
    }

    private boolean expectsException(Test annotation) {
        return getExpectedException(annotation) != null;
    }

    private long getTimeout(Test annotation) {
        if (annotation == null) {
            return 0;
        }
        return annotation.timeout();
    }
}