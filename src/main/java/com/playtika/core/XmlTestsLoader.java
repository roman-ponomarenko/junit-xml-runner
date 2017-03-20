package com.playtika.core;

import com.jcabi.xml.XML;
import com.jcabi.xml.XMLDocument;
import com.playtika.core.exceptions.ClassWithTestsNotFound;
import com.playtika.core.exceptions.NonExistentTestsDetected;
import com.playtika.core.exceptions.XmlWithTestsNotFound;
import com.playtika.core.exceptions.XmlTestClassesNotFound;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class XmlTestsLoader {
    private static final String SUITES_FOLDER = "./src/test/resources/suites/";
    private static final String CLASS_XPATH = "//suite/test/classes/class";
    private static final String INCLUDE_METHODS_XPATH = "methods/include/@name";
    private static final String NAME_XPATH = "@name";
    private final Map<Class<?>, Collection<String>> tests = new LinkedHashMap<>();
    private final Logger logger = LoggerFactory.getLogger(XmlTestsLoader.class);

    private XmlTestsLoader() {
        load();
    }

    private List<XML> getXMLNodes() {
        XML xml;
        try {
            xml = new XMLDocument(new File(SUITES_FOLDER, System.getProperty("testsXml")));
        } catch (IOException e) {
            logger.error("Unable to find xml file with tests.", e);
            throw new XmlWithTestsNotFound(e.getMessage());
        }

        List<XML> nodes = xml.nodes(CLASS_XPATH);
        if(nodes.isEmpty()) {
            throw new XmlTestClassesNotFound(String.format("Test class not found in xml by using %s locator.", CLASS_XPATH));
        }

        return nodes;
    }

    private Class<?> getClass(String xmlClazz) {
        Class<?> clazz;
        try {
            clazz = Class.forName(xmlClazz);
        } catch (ClassNotFoundException e) {
            logger.error("Unable to find associated class with the given string name.", e);
            throw new ClassWithTestsNotFound(e.getMessage());
        }
        return clazz;
    }

    private void load() {
        for (XML node : getXMLNodes()) {
            String xmlClazz = node.xpath(NAME_XPATH).get(0);
            List<String> xmlMethods = node.xpath(INCLUDE_METHODS_XPATH);
            Class<?> clazz = getClass(xmlClazz);

            List<String> allTestsInClass = Stream.of(clazz.getMethods())
                    .filter(p -> p.isAnnotationPresent(Test.class))
                    .map(Method::getName).collect(Collectors.toList());

            if (xmlMethods.isEmpty()) {
                tests.put(clazz, allTestsInClass);
            } else {
                List<String> invalidXmlTests = new ArrayList<>();
                xmlMethods.forEach(p -> {
                    if (!allTestsInClass.stream().anyMatch(x -> x.equals(p))) {
                        invalidXmlTests.add(p);
                    }
                });
                if (!invalidXmlTests.isEmpty()) {
                    throw new NonExistentTestsDetected(String.format("Invalid test(s) was/were specified in xml file: %s.",
                            invalidXmlTests.stream().collect(Collectors.joining(", "))));
                }
                tests.put(clazz, xmlMethods);
            }
        }
    }

    public static XmlTestsLoader getInstance() {
        return XmlTestsLoader.LazyHolder.INSTANCE;
    }

    public Map<Class<?>, Collection<String>> getTests() {
        return tests;
    }

    private static class LazyHolder {
        private static final XmlTestsLoader INSTANCE = new XmlTestsLoader();

        private LazyHolder() {
        }
    }
}