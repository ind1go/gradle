/*
 * Copyright 2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.testing.testng;

import org.testng.IResultMap;
import org.testng.ISuite;
import org.testng.ITestContext;
import org.testng.ITestNGMethod;
import org.testng.internal.ResultMap;
import org.testng.xml.XmlTest;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;

public class TestDryRunner implements ITestContext {

    private final ISuite suite;

    public TestDryRunner(ISuite suite) {
        this.suite = suite;
    }

    @Override
    public String getName() {
        return suite.getName();
    }

    @Override
    public Date getStartDate() {
        return new Date();
    }

    @Override
    public Date getEndDate() {
        return new Date();
    }

    @Override
    public IResultMap getPassedTests() {
        return new ResultMap();
    }

    @Override
    public IResultMap getSkippedTests() {
        return new ResultMap();
    }

    @Override
    public IResultMap getFailedButWithinSuccessPercentageTests() {
        return new ResultMap();
    }

    @Override
    public IResultMap getFailedTests() {
        return null;
    }

    @Override
    public String[] getIncludedGroups() {
        return new String[0];
    }

    @Override
    public String[] getExcludedGroups() {
        return new String[0];
    }

    @Override
    public String getOutputDirectory() {
        return suite.getOutputDirectory();
    }

    @Override
    public ISuite getSuite() {
        return suite;
    }

    @Override
    public ITestNGMethod[] getAllTestMethods() {
        return suite.getAllMethods().toArray(new ITestNGMethod[0]);
    }

    @Override
    public String getHost() {
        return suite.getHost();
    }

    @Override
    public Collection<ITestNGMethod> getExcludedMethods() {
        return null;
    }

    @Override
    public IResultMap getPassedConfigurations() {
        return new ResultMap();
    }

    @Override
    public IResultMap getSkippedConfigurations() {
        return new ResultMap();
    }

    @Override
    public IResultMap getFailedConfigurations() {
        return new ResultMap();
    }

    @Override
    public XmlTest getCurrentXmlTest() {
        return null;
    }

    @Override
    public void addInjector(List<com.google.inject.Module> moduleInstances, com.google.inject.Injector injector) {

    }

    @Override
    public com.google.inject.Injector getInjector(List<com.google.inject.Module> moduleInstances) {
        return null;
    }

    @Override
    public void addGuiceModule(Class<? extends com.google.inject.Module> cls, com.google.inject.Module module) {

    }

    @Override
    public List<com.google.inject.Module> getGuiceModules(Class<? extends com.google.inject.Module> cls) {
        return null;
    }

    @Override
    public Object getAttribute(String name) {
        return null;
    }

    @Override
    public void setAttribute(String name, Object value) {

    }

    @Override
    public Set<String> getAttributeNames() {
        return null;
    }

    @Override
    public Object removeAttribute(String name) {
        return null;
    }
}
