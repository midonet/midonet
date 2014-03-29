/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.config;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.config.providers.HierarchicalConfigurationProvider;

/**
 * Builder that allow building an config object when given an annotated interface
 * and an implementation of a ConfigProvider. Sample usage:
 * <blockquote><pre>
 *   &#064;ConfigGroup("group")
 *   public interface ConfigInterface {
 *
 *      &#064;ConfigInt(key = "key", defaultValue = 10)
 *      int getIntValue();
 *   }
 *
 *   public void method() {
 *       ConfigProvider provider = new HierarchicalConfigurationProvider(config);
 *
 *       ConfigInterface config = provider.getConfig(ConfigInterface.class);
 *
 *       assert 10 == config.getIntValue();
 *
 *       // can reuse the backend with other interfaces
 *       // ConfigInterface2 config2 = provider.getConfig(ConfigInterface2.class);
 *   }
 *
 * </pre></blockquote>
 */
@SuppressWarnings("JavaDoc")
public abstract class ConfigProvider {

    private static final Logger log = LoggerFactory
        .getLogger(ConfigProvider.class);

    public abstract String getValue(String group, String key, String defaultValue);

    public abstract boolean getValue(String group, String key, boolean defaultValue);

    public abstract int getValue(String group, String key, int defaultValue);

    public abstract long getValue(String group, String key, long defaultValue);

    public <Config> Config getConfig(Class<Config> configInterface) {
        return getConfig(configInterface, this);
    }

    public static ConfigProvider providerForIniConfig(HierarchicalConfiguration config) {
        return new HierarchicalConfigurationProvider(config);
    }

    public static ConfigProvider fromIniFile(String path) {
        return providerForIniConfig(fromConfigFile(path));
    }

    public static <C> C configFromIniFile(String path, Class<C> confType) {
        return providerForIniConfig(fromConfigFile(path)).getConfig(confType);
    }

    public static HierarchicalConfiguration fromConfigFile(String path) {
        try {
            HierarchicalINIConfiguration config =
                new HierarchicalINIConfiguration();
            config.setDelimiterParsingDisabled(true);
            config.setFileName(path);
            config.load();
            return config;
        } catch (ConfigurationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <Config> Config getConfig(
            Class<Config> interfaces, final ConfigProvider provider) {

        ClassLoader classLoader = provider.getClass().getClassLoader();

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable {
                return handleInvocation(method, args, provider);
            }
        };

        Class[] intsArray = {interfaces};

        return (Config) Proxy.newProxyInstance(
            classLoader, intsArray, handler);
    }

    private static Object handleInvocation(Method method, Object[] args,
                                           ConfigProvider provider) {

        ConfigInt integerConfigAnn = method.getAnnotation(ConfigInt.class);
        if (integerConfigAnn != null ) {
            return provider.getValue(findDeclaredGroupName(method),
                                     integerConfigAnn.key(),
                                     integerConfigAnn.defaultValue());
        }

        ConfigLong longConfigAnn = method.getAnnotation(ConfigLong.class);
        if (longConfigAnn != null ) {
            return provider.getValue(findDeclaredGroupName(method),
                                     longConfigAnn.key(),
                                     longConfigAnn.defaultValue());
        }

        ConfigBool boolConfigAnn = method.getAnnotation(ConfigBool.class);
        if (boolConfigAnn != null ) {
            return provider.getValue(findDeclaredGroupName(method),
                                     boolConfigAnn.key(),
                                     boolConfigAnn.defaultValue());
        }

        ConfigString strConfigAnn = method.getAnnotation(ConfigString.class);
        if (strConfigAnn != null ) {
            return provider.getValue(findDeclaredGroupName(method),
                                     strConfigAnn.key(),
                                     strConfigAnn.defaultValue());
        }

        throw
            new IllegalArgumentException(
                "Method " + method.toGenericString() + " is being used as an " +
                    "config entry accessor but is not annotated with one of the" +
                    "@ConfigInt, @ConfigLong, @ConfigString, @ConfigBool " +
                    "annotations.");
    }

    private static String findDeclaredGroupName(Method method) {

        ConfigGroup configGroup = method.getAnnotation(ConfigGroup.class);
        if (configGroup == null ) {
            configGroup = method.getDeclaringClass().getAnnotation(ConfigGroup.class);
        }

        if (configGroup != null ){
            return configGroup.value();
        }

        throw new IllegalArgumentException(
                "Method " + method.toGenericString() + " is being used as an " +
                    "config entry accessor but is not annotated (or the class " +
                    "declaring it) with @ConfigGroup annotation");
    }
}
