/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.hamcrest;

import java.util.regex.Pattern;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.TypeSafeMatcher;

/**
 * Hamcrest matcher that will match a string against a regular expression.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 11/17/11
 */
public class RegexMatcher {

    /**
     * Does the String match the regular expression?
     */
    @Factory
    public static TypeSafeMatcher<String> matchesRegex(String regex) {
        return new InternalRegexMatcher(regex);
    }

    private static class InternalRegexMatcher extends TypeSafeMatcher<String> {

        private String regex;

        /**
         * Constructor.
         *
         * @param regex The predicate evaluates to true for Strings
         *              matching this regular expression.
         */
        private InternalRegexMatcher(String regex) {
            this.regex = regex;
        }

        @Override
        public boolean matchesSafely(String input) {
            return input != null && Pattern.matches(regex, input);
        }

        @Override
        public void describeTo(Description description) {
            description.appendText(
                "a String matching the regular expression \"" + regex + "\".");
        }
    }
}
