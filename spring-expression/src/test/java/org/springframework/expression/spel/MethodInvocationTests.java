/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.expression.spel;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.core.convert.TypeDescriptor;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionInvocationTargetException;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.MethodFilter;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.expression.spel.testresources.PlaceOfBirth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests invocation of methods.
 *
 * @author Andy Clement
 * @author Phillip Webb
 * @author Sam Brannen
 */
public class MethodInvocationTests extends AbstractExpressionTests {

	@Test
	public void testSimpleAccess01() {
		evaluate("getPlaceOfBirth().getCity()", "SmilJan", String.class);
	}

	@Test
	public void testStringClass() {
		evaluate("new java.lang.String('hello').charAt(2)", 'l', Character.class);
		evaluate("new java.lang.String('hello').charAt(2).equals('l'.charAt(0))", true, Boolean.class);
		evaluate("'HELLO'.toLowerCase()", "hello", String.class);
		evaluate("'   abcba '.trim()", "abcba", String.class);
	}

	@Test
	public void testNonExistentMethods() {
		// name is ok but madeup() does not exist
		evaluateAndCheckError("name.madeup()", SpelMessage.METHOD_NOT_FOUND, 5);
	}

	@Test
	public void testWidening01() {
		// widening of int 3 to double 3 is OK
		evaluate("new Double(3.0d).compareTo(8)", -1, Integer.class);
		evaluate("new Double(3.0d).compareTo(3)", 0, Integer.class);
		evaluate("new Double(3.0d).compareTo(2)", 1, Integer.class);
	}

	@Test
	public void testArgumentConversion01() {
		// Rely on Double>String conversion for calling startsWith()
		evaluate("new String('hello 2.0 to you').startsWith(7.0d)", false, Boolean.class);
		evaluate("new String('7.0 foobar').startsWith(7.0d)", true, Boolean.class);
	}

	@Test
	public void testMethodThrowingException_SPR6760() {
		// Test method on inventor: throwException()
		// On 1 it will throw an IllegalArgumentException
		// On 2 it will throw a RuntimeException
		// On 3 it will exit normally
		// In each case it increments the Inventor field 'counter' when invoked

		SpelExpressionParser parser = new SpelExpressionParser();
		Expression expr = parser.parseExpression("throwException(#bar)");

		// Normal exit
		StandardEvaluationContext eContext = TestScenarioCreator.getTestEvaluationContext();
		eContext.setVariable("bar", 3);
		Object o = expr.getValue(eContext);
		assertThat(o).isEqualTo(3);
		assertThat(parser.parseExpression("counter").getValue(eContext)).isEqualTo(1);

		// Now the expression has cached that throwException(int) is the right thing to call
		// Let's change 'bar' to be a PlaceOfBirth which indicates the cached reference is
		// out of date.
		eContext.setVariable("bar", new PlaceOfBirth("London"));
		o = expr.getValue(eContext);
		assertThat(o).isEqualTo("London");
		// That confirms the logic to mark the cached reference stale and retry is working

		// Now let's cause the method to exit via exception and ensure it doesn't cause a retry.

		// First, switch back to throwException(int)
		eContext.setVariable("bar", 3);
		o = expr.getValue(eContext);
		assertThat(o).isEqualTo(3);
		assertThat(parser.parseExpression("counter").getValue(eContext)).isEqualTo(2);


		// Now cause it to throw an exception:
		eContext.setVariable("bar", 1);
		assertThatException()
			.isThrownBy(() -> expr.getValue(eContext))
			.isNotInstanceOf(SpelEvaluationException.class);

		// If counter is 4 then the method got called twice!
		assertThat(parser.parseExpression("counter").getValue(eContext)).isEqualTo(3);

		eContext.setVariable("bar", 4);
		assertThatExceptionOfType(ExpressionInvocationTargetException.class).isThrownBy(() -> expr.getValue(eContext));

		// If counter is 5 then the method got called twice!
		assertThat(parser.parseExpression("counter").getValue(eContext)).isEqualTo(4);
	}

	/**
	 * Check on first usage (when the cachedExecutor in MethodReference is null) that the exception is not wrapped.
	 */
	@Test
	public void testMethodThrowingException_SPR6941() {
		// Test method on inventor: throwException()
		// On 1 it will throw an IllegalArgumentException
		// On 2 it will throw a RuntimeException
		// On 3 it will exit normally
		// In each case it increments the Inventor field 'counter' when invoked

		SpelExpressionParser parser = new SpelExpressionParser();
		Expression expr = parser.parseExpression("throwException(#bar)");

		context.setVariable("bar", 2);
		assertThatException()
			.isThrownBy(() -> expr.getValue(context))
			.isNotInstanceOf(SpelEvaluationException.class);
	}

	@Test
	public void testMethodThrowingException_SPR6941_2() {
		// Test method on inventor: throwException()
		// On 1 it will throw an IllegalArgumentException
		// On 2 it will throw a RuntimeException
		// On 3 it will exit normally
		// In each case it increments the Inventor field 'counter' when invoked

		SpelExpressionParser parser = new SpelExpressionParser();
		Expression expr = parser.parseExpression("throwException(#bar)");

		context.setVariable("bar", 4);
		assertThatExceptionOfType(ExpressionInvocationTargetException.class)
			.isThrownBy(() -> expr.getValue(context))
			.satisfies(ex -> assertThat(ex.getCause().getClass().getName()).isEqualTo(
					"org.springframework.expression.spel.testresources.Inventor$TestException"));
	}

	@Test
	public void testMethodFiltering_SPR6764() {
		SpelExpressionParser parser = new SpelExpressionParser();
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setRootObject(new TestObject());
		LocalFilter filter = new LocalFilter();
		context.registerMethodFilter(TestObject.class,filter);

		// Filter will be called but not do anything, so first doit() will be invoked
		SpelExpression expr = (SpelExpression) parser.parseExpression("doit(1)");
		String result = expr.getValue(context, String.class);
		assertThat(result).isEqualTo("1");
		assertThat(filter.filterCalled).isTrue();

		// Filter will now remove non @Anno annotated methods
		filter.removeIfNotAnnotated = true;
		filter.filterCalled = false;
		expr = (SpelExpression) parser.parseExpression("doit(1)");
		result = expr.getValue(context, String.class);
		assertThat(result).isEqualTo("double 1.0");
		assertThat(filter.filterCalled).isTrue();

		// check not called for other types
		filter.filterCalled = false;
		context.setRootObject(new String("abc"));
		expr = (SpelExpression) parser.parseExpression("charAt(0)");
		result = expr.getValue(context, String.class);
		assertThat(result).isEqualTo("a");
		assertThat(filter.filterCalled).isFalse();

		// check de-registration works
		filter.filterCalled = false;
		context.registerMethodFilter(TestObject.class,null);//clear filter
		context.setRootObject(new TestObject());
		expr = (SpelExpression) parser.parseExpression("doit(1)");
		result = expr.getValue(context, String.class);
		assertThat(result).isEqualTo("1");
		assertThat(filter.filterCalled).isFalse();
	}

	@Test
	public void testAddingMethodResolvers() {
		StandardEvaluationContext ctx = new StandardEvaluationContext();

		// reflective method accessor is the only one by default
		List<MethodResolver> methodResolvers = ctx.getMethodResolvers();
		assertThat(methodResolvers).hasSize(1);

		MethodResolver dummy = new DummyMethodResolver();
		ctx.addMethodResolver(dummy);
		assertThat(ctx.getMethodResolvers()).hasSize(2);

		List<MethodResolver> copy = new ArrayList<>(ctx.getMethodResolvers());
		assertThat(ctx.removeMethodResolver(dummy)).isTrue();
		assertThat(ctx.removeMethodResolver(dummy)).isFalse();
		assertThat(ctx.getMethodResolvers()).hasSize(1);

		ctx.setMethodResolvers(copy);
		assertThat(ctx.getMethodResolvers()).hasSize(2);
	}

	@Test
	public void testVarargsInvocation01() {
		// Calling 'public String aVarargsMethod(String... strings)'
		evaluate("aVarargsMethod('a','b','c')", "[a, b, c]", String.class);
		evaluate("aVarargsMethod('a')", "[a]", String.class);
		evaluate("aVarargsMethod()", "[]", String.class);
		evaluate("aVarargsMethod(1,2,3)", "[1, 2, 3]", String.class); // all need converting to strings
		evaluate("aVarargsMethod(1)", "[1]", String.class); // needs string conversion
		evaluate("aVarargsMethod(1,'a',3.0d)", "[1, a, 3.0]", String.class); // first and last need conversion
		evaluate("aVarargsMethod(new String[]{'a','b','c'})", "[a, b, c]", String.class);
		evaluate("aVarargsMethod(new String[]{})", "[]", String.class);
		evaluate("aVarargsMethod(null)", "[null]", String.class);
		evaluate("aVarargsMethod(null,'a')", "[null, a]", String.class);
		evaluate("aVarargsMethod('a',null,'b')", "[a, null, b]", String.class);
	}

	@Test
	public void testVarargsInvocation02() {
		// Calling 'public String aVarargsMethod2(int i, String... strings)'
		evaluate("aVarargsMethod2(5,'a','b','c')", "5-[a, b, c]", String.class);
		evaluate("aVarargsMethod2(2,'a')", "2-[a]", String.class);
		evaluate("aVarargsMethod2(4)", "4-[]", String.class);
		evaluate("aVarargsMethod2(8,2,3)", "8-[2, 3]", String.class);
		evaluate("aVarargsMethod2(2,'a',3.0d)", "2-[a, 3.0]", String.class);
		evaluate("aVarargsMethod2(8,new String[]{'a','b','c'})", "8-[a, b, c]", String.class);
		evaluate("aVarargsMethod2(8,new String[]{})", "8-[]", String.class);
		evaluate("aVarargsMethod2(8,null)", "8-[null]", String.class);
		evaluate("aVarargsMethod2(8,null,'a')", "8-[null, a]", String.class);
		evaluate("aVarargsMethod2(8,'a',null,'b')", "8-[a, null, b]", String.class);
	}

	@Test
	public void testVarargsInvocation03() {
		// Calling 'public int aVarargsMethod3(String str1, String... strings)' - returns all strings concatenated with "-"

		// No conversion necessary
		evaluate("aVarargsMethod3('x')", "x", String.class);
		evaluate("aVarargsMethod3('x', 'a')", "x-a", String.class);
		evaluate("aVarargsMethod3('x', 'a', 'b', 'c')", "x-a-b-c", String.class);

		// Conversion necessary
		evaluate("aVarargsMethod3(9)", "9", String.class);
		evaluate("aVarargsMethod3(8,2,3)", "8-2-3", String.class);
		evaluate("aVarargsMethod3('2','a',3.0d)", "2-a-3.0", String.class);
		evaluate("aVarargsMethod3('8',new String[]{'a','b','c'})", "8-a-b-c", String.class);

		// Individual string contains a comma with multiple varargs arguments
		evaluate("aVarargsMethod3('foo', ',', 'baz')", "foo-,-baz", String.class);
		evaluate("aVarargsMethod3('foo', 'bar', ',baz')", "foo-bar-,baz", String.class);
		evaluate("aVarargsMethod3('foo', 'bar,', 'baz')", "foo-bar,-baz", String.class);

		// Individual string contains a comma with single varargs argument.
		// Reproduces https://github.com/spring-projects/spring-framework/issues/27582
		evaluate("aVarargsMethod3('foo', ',')", "foo-,", String.class);
		evaluate("aVarargsMethod3('foo', ',bar')", "foo-,bar", String.class);
		evaluate("aVarargsMethod3('foo', 'bar,')", "foo-bar,", String.class);
		evaluate("aVarargsMethod3('foo', 'bar,baz')", "foo-bar,baz", String.class);
	}

	@Test  // gh-33013
	void testVarargsWithObjectArrayType() {
		// Calling 'public String formatObjectVarargs(String format, Object... args)' -> String.format(format, args)

		// No var-args and no conversion necessary
		evaluate("formatObjectVarargs('x')", "x", String.class);

		// No var-args but conversion necessary
		evaluate("formatObjectVarargs(9)", "9", String.class);

		// No conversion necessary
		evaluate("formatObjectVarargs('x -> %s', '')", "x -> ", String.class);
		evaluate("formatObjectVarargs('x -> %s', ' ')", "x ->  ", String.class);
		evaluate("formatObjectVarargs('x -> %s', 'a')", "x -> a", String.class);
		evaluate("formatObjectVarargs('x -> %s %s %s', 'a', 'b', 'c')", "x -> a b c", String.class);
		evaluate("formatObjectVarargs('x -> %s', new Object[]{''})", "x -> ", String.class);
		evaluate("formatObjectVarargs('x -> %s', new Object[]{' '})", "x ->  ", String.class);
		evaluate("formatObjectVarargs('x -> %s', new Object[]{'a'})", "x -> a", String.class);
		evaluate("formatObjectVarargs('x -> %s %s %s', new Object[]{'a', 'b', 'c'})", "x -> a b c", String.class);

		// The following assertions were cherry-picked from 6.1.x; however, they are expected
		// to fail on 6.0.x and 5.3.x, since gh-32704 (Support varargs invocations in SpEL for
		// varargs array subtype) was not backported.
		// evaluate("formatObjectVarargs('x -> %s', new String[]{''})", "x -> ", String.class);
		// evaluate("formatObjectVarargs('x -> %s', new String[]{' '})", "x ->  ", String.class);
		// evaluate("formatObjectVarargs('x -> %s', new String[]{'a'})", "x -> a", String.class);
		// evaluate("formatObjectVarargs('x -> %s %s %s', new String[]{'a', 'b', 'c'})", "x -> a b c", String.class);

		// Conversion necessary
		evaluate("formatObjectVarargs('x -> %s %s', 2, 3)", "x -> 2 3", String.class);
		evaluate("formatObjectVarargs('x -> %s %s', 'a', 3.0d)", "x -> a 3.0", String.class);

		// Individual string contains a comma with multiple varargs arguments
		evaluate("formatObjectVarargs('foo -> %s %s', ',', 'baz')", "foo -> , baz", String.class);
		evaluate("formatObjectVarargs('foo -> %s %s', 'bar', ',baz')", "foo -> bar ,baz", String.class);
		evaluate("formatObjectVarargs('foo -> %s %s', 'bar,', 'baz')", "foo -> bar, baz", String.class);

		// Individual string contains a comma with single varargs argument.
		evaluate("formatObjectVarargs('foo -> %s', ',')", "foo -> ,", String.class);
		evaluate("formatObjectVarargs('foo -> %s', ',bar')", "foo -> ,bar", String.class);
		evaluate("formatObjectVarargs('foo -> %s', 'bar,')", "foo -> bar,", String.class);
		evaluate("formatObjectVarargs('foo -> %s', 'bar,baz')", "foo -> bar,baz", String.class);
	}

	@Test
	public void testVarargsOptionalInvocation() {
		// Calling 'public String optionalVarargsMethod(Optional<String>... values)'
		evaluate("optionalVarargsMethod()", "[]", String.class);
		evaluate("optionalVarargsMethod(new String[0])", "[]", String.class);
		evaluate("optionalVarargsMethod('a')", "[Optional[a]]", String.class);
		evaluate("optionalVarargsMethod('a','b','c')", "[Optional[a], Optional[b], Optional[c]]", String.class);
		evaluate("optionalVarargsMethod(9)", "[Optional[9]]", String.class);
		evaluate("optionalVarargsMethod(2,3)", "[Optional[2], Optional[3]]", String.class);
		evaluate("optionalVarargsMethod('a',3.0d)", "[Optional[a], Optional[3.0]]", String.class);
		evaluate("optionalVarargsMethod(new String[]{'a','b','c'})", "[Optional[a], Optional[b], Optional[c]]", String.class);
		evaluate("optionalVarargsMethod(null)", "[Optional.empty]", String.class);
		evaluate("optionalVarargsMethod(null,'a')", "[Optional.empty, Optional[a]]", String.class);
		evaluate("optionalVarargsMethod('a',null,'b')", "[Optional[a], Optional.empty, Optional[b]]", String.class);
	}

	@Test
	public void testInvocationOnNullContextObject() {
		evaluateAndCheckError("null.toString()",SpelMessage.METHOD_CALL_ON_NULL_OBJECT_NOT_ALLOWED);
	}

	@Test
	public void testMethodOfClass() throws Exception {
		Expression expression = parser.parseExpression("getName()");
		Object value = expression.getValue(new StandardEvaluationContext(String.class));
		assertThat(value).isEqualTo("java.lang.String");
	}

	@Test
	public void invokeMethodWithoutConversion() throws Exception {
		final BytesService service = new BytesService();
		byte[] bytes = new byte[100];
		StandardEvaluationContext context = new StandardEvaluationContext(bytes);
		context.setBeanResolver((context1, beanName) -> ("service".equals(beanName) ? service : null));
		Expression expression = parser.parseExpression("@service.handleBytes(#root)");
		byte[] outBytes = expression.getValue(context, byte[].class);
		assertThat(outBytes).isSameAs(bytes);
	}


	// Simple filter
	static class LocalFilter implements MethodFilter {

		public boolean removeIfNotAnnotated = false;

		public boolean filterCalled = false;

		private boolean isAnnotated(Method method) {
			Annotation[] anns = method.getAnnotations();
			if (anns == null) {
				return false;
			}
			for (Annotation ann : anns) {
				String name = ann.annotationType().getName();
				if (name.endsWith("Anno")) {
					return true;
				}
			}
			return false;
		}

		@Override
		public List<Method> filter(List<Method> methods) {
			filterCalled = true;
			List<Method> forRemoval = new ArrayList<>();
			for (Method method: methods) {
				if (removeIfNotAnnotated && !isAnnotated(method)) {
					forRemoval.add(method);
				}
			}
			for (Method method: forRemoval) {
				methods.remove(method);
			}
			return methods;
		}
	}


	@Retention(RetentionPolicy.RUNTIME)
	@interface Anno {
	}


	class TestObject {

		public int doit(int i) {
			return i;
		}

		@Anno
		public String doit(double d) {
			return "double "+d;
		}
	}


	static class DummyMethodResolver implements MethodResolver {

		@Override
		public MethodExecutor resolve(EvaluationContext context, Object targetObject, String name,
				List<TypeDescriptor> argumentTypes) throws AccessException {
			throw new UnsupportedOperationException();
		}
	}


	public static class BytesService {

		public byte[] handleBytes(byte[] bytes) {
			return bytes;
		}
	}

}
