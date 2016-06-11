/*
 * Copyright 2015-2016 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution and is available at
 *
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.junit.gen5.junit4.runner;

import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.gen5.api.Assertions.assertEquals;
import static org.junit.gen5.api.Assertions.assertThrows;
import static org.junit.gen5.api.Assertions.assertTrue;
import static org.junit.gen5.api.Assumptions.assumeFalse;
import static org.junit.gen5.commons.util.CollectionUtils.getOnlyElement;
import static org.junit.gen5.engine.TestExecutionResult.successful;
import static org.junit.gen5.launcher.main.LauncherFactoryForTestingPurposesOnly.createLauncher;
import static org.junit.runner.Description.createSuiteDescription;
import static org.junit.runner.Description.createTestDescription;
import static org.junit.runner.manipulation.Filter.matchMethodDescription;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.gen5.api.Nested;
import org.junit.gen5.api.Test;
import org.junit.gen5.engine.EngineExecutionListener;
import org.junit.gen5.engine.ExecutionRequest;
import org.junit.gen5.engine.TestDescriptor;
import org.junit.gen5.engine.TestDescriptorStub;
import org.junit.gen5.engine.TestEngine;
import org.junit.gen5.engine.TestTag;
import org.junit.gen5.engine.UniqueId;
import org.junit.gen5.engine.discovery.ClassFilter;
import org.junit.gen5.engine.discovery.ClassSelector;
import org.junit.gen5.engine.discovery.PackageSelector;
import org.junit.gen5.engine.discovery.UniqueIdSelector;
import org.junit.gen5.engine.junit5.stubs.TestEngineStub;
import org.junit.gen5.engine.support.descriptor.EngineDescriptor;
import org.junit.gen5.engine.support.hierarchical.DummyTestEngine;
import org.junit.gen5.launcher.EngineFilter;
import org.junit.gen5.launcher.Launcher;
import org.junit.gen5.launcher.PostDiscoveryFilter;
import org.junit.gen5.launcher.TestDiscoveryRequest;
import org.junit.gen5.launcher.TestPlan;
import org.junit.runner.Description;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Tests for the {@link JUnit5} runner.
 *
 * @since 5.0
 */
class JUnit5Tests {

	@Nested
	class Discovery {

		@Test
		void requestsClassSelectorForAnnotatedClassWhenNoAdditionalAnnotationsArePresent() throws Exception {

			class TestCase {
			}

			TestDiscoveryRequest request = instantiateRunnerAndCaptureGeneratedRequest(TestCase.class);

			assertThat(request.getSelectors()).hasSize(1);
			ClassSelector classSelector = getOnlyElement(request.getSelectorsByType(ClassSelector.class));
			assertEquals(TestCase.class, classSelector.getJavaClass());
		}

		@Test
		void requestsClassSelectorsWhenClassesAnnotationIsPresent() throws Exception {

			@Classes({ Short.class, Byte.class })
			class TestCase {
			}

			TestDiscoveryRequest request = instantiateRunnerAndCaptureGeneratedRequest(TestCase.class);

			assertThat(request.getSelectors()).hasSize(2);
			List<ClassSelector> selectors = request.getSelectorsByType(ClassSelector.class);
			assertEquals(Short.class, selectors.get(0).getJavaClass());
			assertEquals(Byte.class, selectors.get(1).getJavaClass());
		}

		@Test
		void requestsUniqueIdSelectorsWhenUniqueIdsAnnotationIsPresent() throws Exception {

			@UniqueIds({ "[foo:bar]", "[baz:quux]" })
			class TestCase {
			}

			TestDiscoveryRequest request = instantiateRunnerAndCaptureGeneratedRequest(TestCase.class);

			assertThat(request.getSelectors()).hasSize(2);
			List<UniqueIdSelector> selectors = request.getSelectorsByType(UniqueIdSelector.class);
			assertEquals("[foo:bar]", selectors.get(0).getUniqueId().toString());
			assertEquals("[baz:quux]", selectors.get(1).getUniqueId().toString());
		}

		@Test
		void requestsPackageSelectorsWhenPackagesAnnotationIsPresent() throws Exception {

			@Packages({ "foo", "bar" })
			class TestCase {
			}

			TestDiscoveryRequest request = instantiateRunnerAndCaptureGeneratedRequest(TestCase.class);

			assertThat(request.getSelectors()).hasSize(2);
			List<PackageSelector> selectors = request.getSelectorsByType(PackageSelector.class);
			assertEquals("foo", selectors.get(0).getPackageName());
			assertEquals("bar", selectors.get(1).getPackageName());
		}

		@Test
		void addsTagFilterToRequestWhenIncludeTagsAnnotationIsPresent() throws Exception {

			@IncludeTags({ "foo", "bar" })
			class TestCase {
			}

			TestDiscoveryRequest request = instantiateRunnerAndCaptureGeneratedRequest(TestCase.class);

			List<PostDiscoveryFilter> filters = request.getPostDiscoveryFilters();
			assertThat(filters).hasSize(1);

			PostDiscoveryFilter filter = filters.get(0);
			assertTrue(filter.apply(testDescriptorWithTag("foo")).included());
			assertTrue(filter.apply(testDescriptorWithTag("bar")).included());
			assertTrue(filter.apply(testDescriptorWithTag("baz")).excluded());
		}

		@Test
		void addsTagFilterToRequestWhenExcludeTagsAnnotationIsPresent() throws Exception {

			@ExcludeTags({ "foo", "bar" })
			class TestCase {
			}

			TestDiscoveryRequest request = instantiateRunnerAndCaptureGeneratedRequest(TestCase.class);

			List<PostDiscoveryFilter> filters = request.getPostDiscoveryFilters();
			assertThat(filters).hasSize(1);

			PostDiscoveryFilter filter = filters.get(0);
			assertTrue(filter.apply(testDescriptorWithTag("foo")).excluded());
			assertTrue(filter.apply(testDescriptorWithTag("bar")).excluded());
			assertTrue(filter.apply(testDescriptorWithTag("baz")).included());
		}

		@Test
		void addsEngineFiltersToRequestWhenIncludeEnginesOrExcludeEnginesAnnotationsArePresent() throws Exception {

			@IncludeEngines({ "foo", "bar", "baz" })
			@ExcludeEngines({ "bar", "quux" })
			class TestCase {
			}

			TestEngine fooEngine = new TestEngineStub("foo");
			TestEngine barEngine = new TestEngineStub("bar");
			TestEngine bazEngine = new TestEngineStub("baz");
			TestEngine quuxEngine = new TestEngineStub("quux");

			TestDiscoveryRequest request = instantiateRunnerAndCaptureGeneratedRequest(TestCase.class);

			List<EngineFilter> filters = request.getEngineFilters();
			assertThat(filters).hasSize(2);

			EngineFilter includeFilter = filters.get(0);
			assertTrue(includeFilter.apply(fooEngine).included());
			assertTrue(includeFilter.apply(barEngine).included());
			assertTrue(includeFilter.apply(bazEngine).included());
			assertTrue(includeFilter.apply(quuxEngine).excluded());

			EngineFilter excludeFilter = filters.get(1);
			assertTrue(excludeFilter.apply(fooEngine).included());
			assertTrue(excludeFilter.apply(barEngine).excluded());
			assertTrue(excludeFilter.apply(bazEngine).included());
			assertTrue(excludeFilter.apply(quuxEngine).excluded());
		}

		@Test
		void addsClassFilterToRequestWhenFilterClassNameAnnotationIsPresent() throws Exception {

			@FilterClassName(".*Foo")
			class TestCase {
			}
			class Foo {
			}
			class Bar {
			}

			TestDiscoveryRequest request = instantiateRunnerAndCaptureGeneratedRequest(TestCase.class);

			List<ClassFilter> filters = request.getDiscoveryFiltersByType(ClassFilter.class);
			assertThat(filters).hasSize(1);

			ClassFilter filter = filters.get(0);
			assertTrue(filter.apply(Foo.class).included());
			assertTrue(filter.apply(Bar.class).excluded());
		}

		@Test
		void convertsTestIdentifiersIntoDescriptions() throws Exception {

			TestDescriptor container1 = new TestDescriptorStub(UniqueId.root("root", "container1"), "container1");
			container1.addChild(new TestDescriptorStub(UniqueId.root("root", "test1"), "test1"));
			TestDescriptor container2 = new TestDescriptorStub(UniqueId.root("root", "container2"), "container2");
			container2.addChild(new TestDescriptorStub(UniqueId.root("root", "test2a"), "test2a"));
			container2.addChild(new TestDescriptorStub(UniqueId.root("root", "test2b"), "test2b"));
			TestPlan testPlan = TestPlan.from(asList(container1, container2));

			Launcher launcher = mock(Launcher.class);
			when(launcher.discover(any())).thenReturn(testPlan);

			JUnit5 runner = new JUnit5(TestClass.class, launcher);

			Description runnerDescription = runner.getDescription();
			assertEquals(createSuiteDescription(TestClass.class), runnerDescription);

			List<Description> containerDescriptions = runnerDescription.getChildren();
			assertThat(containerDescriptions).hasSize(2);
			assertEquals(suiteDescription("[root:container1]"), containerDescriptions.get(0));
			assertEquals(suiteDescription("[root:container2]"), containerDescriptions.get(1));

			List<Description> testDescriptions = containerDescriptions.get(0).getChildren();
			assertEquals(testDescription("[root:test1]"), getOnlyElement(testDescriptions));

			testDescriptions = containerDescriptions.get(1).getChildren();
			assertThat(testDescriptions).hasSize(2);
			assertEquals(testDescription("[root:test2a]"), testDescriptions.get(0));
			assertEquals(testDescription("[root:test2b]"), testDescriptions.get(1));
		}

	}

	@Nested
	class Filtering {

		@Test
		void appliesFilter() throws Exception {

			TestDescriptor originalParent1 = new TestDescriptorStub(UniqueId.root("root", "parent1"), "parent1");
			originalParent1.addChild(new TestDescriptorStub(UniqueId.root("root", "leaf1"), "leaf1"));
			TestDescriptor originalParent2 = new TestDescriptorStub(UniqueId.root("root", "parent2"), "parent2");
			originalParent2.addChild(new TestDescriptorStub(UniqueId.root("root", "leaf2a"), "leaf2a"));
			originalParent2.addChild(new TestDescriptorStub(UniqueId.root("root", "leaf2b"), "leaf2b"));
			TestPlan fullTestPlan = TestPlan.from(asList(originalParent1, originalParent2));

			TestDescriptor filteredParent = new TestDescriptorStub(UniqueId.root("root", "parent2"), "parent2");
			filteredParent.addChild(new TestDescriptorStub(UniqueId.root("root", "leaf2b"), "leaf2b"));
			TestPlan filteredTestPlan = TestPlan.from(singleton(filteredParent));

			Launcher launcher = mock(Launcher.class);
			ArgumentCaptor<TestDiscoveryRequest> captor = ArgumentCaptor.forClass(TestDiscoveryRequest.class);
			when(launcher.discover(captor.capture())).thenReturn(fullTestPlan).thenReturn(filteredTestPlan);

			JUnit5 runner = new JUnit5(TestClass.class, launcher);
			runner.filter(matchMethodDescription(testDescription("[root:leaf2b]")));

			TestDiscoveryRequest lastDiscoveryRequest = captor.getValue();
			List<UniqueIdSelector> uniqueIdSelectors = lastDiscoveryRequest.getSelectorsByType(UniqueIdSelector.class);
			assertEquals("[root:leaf2b]", getOnlyElement(uniqueIdSelectors).getUniqueId().toString());

			Description parentDescription = getOnlyElement(runner.getDescription().getChildren());
			assertEquals(suiteDescription("[root:parent2]"), parentDescription);

			Description testDescription = getOnlyElement(parentDescription.getChildren());
			assertEquals(testDescription("[root:leaf2b]"), testDescription);
		}

		@Test
		void throwsNoTestsRemainExceptionWhenNoTestIdentifierMatchesFilter() throws Exception {
			TestPlan testPlan = TestPlan.from(singleton(new TestDescriptorStub(UniqueId.root("root", "test"), "test")));

			Launcher launcher = mock(Launcher.class);
			when(launcher.discover(any())).thenReturn(testPlan);

			JUnit5 runner = new JUnit5(TestClass.class, launcher);

			assertThrows(NoTestsRemainException.class,
				() -> runner.filter(matchMethodDescription(suiteDescription("[root:doesNotExist]"))));
		}

	}

	@Nested
	class Execution {

		@Test
		void notifiesRunListenerOfTestExecution() throws Exception {
			DummyTestEngine engine = new DummyTestEngine("dummy");
			engine.addTest("failingTest", () -> fail("expected to fail"));
			engine.addTest("succeedingTest", () -> {
			});
			engine.addTest("skippedTest", () -> assumeFalse(true));
			engine.addTest("ignoredTest", () -> fail("never called")).markSkipped("should be skipped");

			RunListener runListener = mock(RunListener.class);

			RunNotifier notifier = new RunNotifier();
			notifier.addListener(runListener);
			new JUnit5(TestClass.class, createLauncher(engine)).run(notifier);

			InOrder inOrder = inOrder(runListener);

			inOrder.verify(runListener).testStarted(testDescription("[engine:dummy]/[test:failingTest]"));
			inOrder.verify(runListener).testFailure(any());
			inOrder.verify(runListener).testFinished(testDescription("[engine:dummy]/[test:failingTest]"));

			inOrder.verify(runListener).testStarted(testDescription("[engine:dummy]/[test:succeedingTest]"));
			inOrder.verify(runListener).testFinished(testDescription("[engine:dummy]/[test:succeedingTest]"));

			inOrder.verify(runListener).testStarted(testDescription("[engine:dummy]/[test:skippedTest]"));
			inOrder.verify(runListener).testAssumptionFailure(any());
			inOrder.verify(runListener).testFinished(testDescription("[engine:dummy]/[test:skippedTest]"));

			inOrder.verify(runListener).testIgnored(testDescription("[engine:dummy]/[test:ignoredTest]"));

			inOrder.verifyNoMoreInteractions();
		}

		@Test
		void reportsIgnoredEventsForLeafsWhenContainerIsSkipped() throws Exception {
			UniqueId uniqueEngineId = UniqueId.forEngine("engine");
			TestDescriptor engineDescriptor = new EngineDescriptor(uniqueEngineId, "engine");
			TestDescriptor container = new TestDescriptorStub(UniqueId.root("root", "container"), "container");
			container.addChild(new TestDescriptorStub(UniqueId.root("root", "leaf"), "leaf"));
			engineDescriptor.addChild(container);

			TestEngine engine = mock(TestEngine.class);
			when(engine.getId()).thenReturn("engine");
			when(engine.discover(any(), eq(uniqueEngineId))).thenReturn(engineDescriptor);
			doAnswer(invocation -> {
				ExecutionRequest request = invocation.getArgumentAt(0, ExecutionRequest.class);
				EngineExecutionListener listener = request.getEngineExecutionListener();
				listener.executionStarted(engineDescriptor);
				listener.executionSkipped(container, "deliberately skipped container");
				listener.executionFinished(engineDescriptor, successful());
				return null;
			}).when(engine).execute(any());

			RunListener runListener = mock(RunListener.class);

			RunNotifier notifier = new RunNotifier();
			notifier.addListener(runListener);
			new JUnit5(TestClass.class, createLauncher(engine)).run(notifier);

			verify(runListener).testIgnored(testDescription("[root:leaf]"));
			verifyNoMoreInteractions(runListener);
		}

	}

	private static Description suiteDescription(String uniqueId) {
		return createSuiteDescription(uniqueId, uniqueId);
	}

	private static Description testDescription(String uniqueId) {
		return createTestDescription(uniqueId, uniqueId, uniqueId);
	}

	private TestDescriptor testDescriptorWithTag(String tag) {
		TestDescriptor testDescriptor = mock(TestDescriptor.class);
		when(testDescriptor.getTags()).thenReturn(singleton(TestTag.of(tag)));
		return testDescriptor;
	}

	private TestDiscoveryRequest instantiateRunnerAndCaptureGeneratedRequest(Class<?> testClass)
			throws InitializationError {
		Launcher launcher = mock(Launcher.class);
		ArgumentCaptor<TestDiscoveryRequest> captor = ArgumentCaptor.forClass(TestDiscoveryRequest.class);
		when(launcher.discover(captor.capture())).thenReturn(TestPlan.from(emptySet()));

		new JUnit5(testClass, launcher);

		return captor.getValue();
	}

	private static class TestClass {
	}

}
