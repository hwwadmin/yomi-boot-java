package com.yomi.boot.java.base.utils;

import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 反射工具类
 *
 * @author zwei
 */
public abstract class ReflectUtils {

    private ReflectUtils() {
    }

    /**
     * 创建class实例对象
     */
    public static <T> T newInstance(String clazz, Object... params) {
        Assert.nonNull(clazz);
        try {
            //noinspection unchecked
            return (T) newInstance(Class.forName(clazz), params);
        } catch (Exception e) {
            throw new IllegalArgumentException("ReflectUtils#newInstance", e);
        }
    }

    /**
     * 创建class实例对象
     */
    public static <T> T newInstance(Class<T> clazz, Object... params) {
        Assert.nonNull(clazz);
        try {
            return newInstance0(clazz, params);
        } catch (Exception e) {
            throw new IllegalArgumentException("ReflectUtils#newInstance", e);
        }
    }

    /**
     * type转class
     */
    public static Class<?> type2class(Type type) {
        Assert.nonNull(type);
        try {
            return toClass(type);
        } catch (Exception e) {
            throw new IllegalArgumentException("ReflectUtils#toClass", e);
        }
    }

    /**
     * 判断对象是否是某个类的同类或子类
     */
    public static boolean isSubClass(Object subObject, Class<?> parentClass) {
        Assert.nonNull(subObject);
        return isSubClass(subObject.getClass(), parentClass);
    }

    /**
     * 判断类是否是某个类的同类或子类
     * clazz 是否是 parentClass 的子类、实现类，或者与 parentClass 相同类
     */
    public static boolean isSubClass(Class<?> subClass, Class<?> parentClass) {
        Assert.nonNull(subClass);
        Assert.nonNull(parentClass);
        return parentClass.isAssignableFrom(subClass);
    }


    /**
     * 获取字段
     */
    public static Field getField(Class<?> clazz, String fieldName) {
        Assert.nonNull(clazz);
        Assert.nonNull(fieldName);
        try {
            return getField0(clazz, fieldName);
        } catch (Exception e) {
            throw new IllegalArgumentException("ReflectUtils#getField", e);
        }
    }

    /**
     * 获取所有字段
     */
    public static Field[] getAllField(Class<?> clazz) {
        Assert.nonNull(clazz);
        try {
            return getFields0(clazz);
        } catch (Exception e) {
            throw new IllegalArgumentException("ReflectUtils#getAllField", e);
        }
    }

    /**
     * 获取字段类型
     */
    public static Class<?> getFieldClass(Class<?> clazz, String fieldName) {
        return getFieldClass(getField(clazz, fieldName));
    }

    /**
     * 获取字段类型
     */
    public static Class<?> getFieldClass(Field field) {
        Assert.nonNull(field);
        return field.getType();
    }

    /**
     * 获取字段值
     */
    public static Object getFieldValue(Object obj, String fieldName) {
        Assert.nonNull(obj);
        Assert.nonNull(fieldName);
        return getFieldValue(obj, getField(obj.getClass(), fieldName));
    }

    /**
     * 获取字段值
     */
    public static Object getFieldValue(Object obj, Field field) {
        Assert.nonNull(obj);
        Assert.nonNull(field);
        try {
            return getFieldValue0(obj, field);
        } catch (Exception e) {
            throw new IllegalArgumentException("ReflectUtils#getFieldValue", e);
        }
    }

    /**
     * 获取字段值
     */
    public static Object getFieldValue(Object obj, Method method, Field field) {
        Assert.nonNull(obj);
        Assert.nonNull(field);
        if (method == null) {
            return getFieldValue(obj, field);
        }
        try {
            return doMethod(obj, method);
        } catch (Exception e) {
            return getFieldValue(obj, field);
        }
    }

    /**
     * 设置字段值
     */
    public static void setFieldValue(Object obj, Field field, Object value) {
        Assert.nonNull(obj);
        Assert.nonNull(field);
        try {
            setFieldValue0(obj, field, value);
        } catch (Exception e) {
            throw new IllegalArgumentException("ReflectUtils#setFieldValue", e);
        }
    }


    /**
     * 获取方法
     */
    public static Method getMethod(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        Assert.nonNull(clazz);
        Assert.nonNull(methodName);
        try {
            return getMethod0(clazz, methodName, paramTypes);
        } catch (Exception e) {
            throw new IllegalArgumentException("ReflectUtils#getMethod", e);
        }
    }

    /**
     * 获取字段对应的getter方法
     */
    public static Method getMethod4FieldGet(Class<?> clazz, Field field) {
        Assert.nonNull(field);
        String propertyName = StrUtils.upperFirst(field.getName());
        Method method = findGetterMethod(clazz, "get" + propertyName);
        if (method == null && isBooleanField(field.getType())) {
            method = findGetterMethod(clazz, "is" + propertyName);
        }
        Assert.isTrue(method != null, "ReflectUtils#getMethod4FieldGet: 未找到字段 {} 的 getter", field.getName());
        return method;
    }

    /**
     * 获取字段对应的setter方法
     */
    public static Method getMethod4FieldSet(Class<?> clazz, Field field, Class<?> paramType) {
        Assert.nonNull(field);
        Assert.nonNull(paramType);
        String methodName = "set" + StrUtils.upperFirst(field.getName());
        Method method = getMethod(clazz, methodName, paramType);
        Assert.isTrue(isGetterOrSetter(method, true));
        return method;
    }

    /**
     * 执行方法
     */
    public static Object doMethod(Object obj, Method method, Object... args) {
        Assert.nonNull(obj);
        Assert.nonNull(method);
        try {
            return invoke0(obj, method, args);
        } catch (Exception e) {
            throw new IllegalArgumentException("ReflectUtils#doMethod", e);
        }
    }

    /**
     * 执行静态方法
     */
    public static Object doStaticMethod(Method method, Object... args) {
        Assert.nonNull(method);
        try {
            return invoke0(null, method, args);
        } catch (Exception e) {
            throw new IllegalArgumentException("ReflectUtils#doStaticMethod", e);
        }
    }

    /**
     * 获取类的第0个参数
     */
    public static Class<?> getClassArgument(Class<?> clazz) {
        return getClassArgument(clazz, 0);
    }

    /**
     * 获取类的第N个参数 0~n
     */
    public static Class<?> getClassArgument(Class<?> clazz, int index) {
        try {
            Type type = getTypeArgument(clazz, index);
            return type2class(type);
        } catch (Exception e) {
            throw new IllegalArgumentException("ReflectUtils#getClassArgument", e);
        }
    }

    /**
     * 获取类的所有参数
     */
    public static Class<?>[] getTypeArguments(Class<?> clazz) {
        try {
            Type[] type = getTypeArguments0(clazz);
            return Arrays.stream(type).map(ReflectUtils::type2class).toArray(Class[]::new);
        } catch (Exception e) {
            throw new IllegalArgumentException("ReflectUtils#getTypeArguments", e);
        }
    }

    /**
     * 按参数匹配并调用声明的构造器创建实例
     */
    private static <T> T newInstance0(Class<T> clazz, Object... params) throws ReflectiveOperationException {
        Object[] args = params == null ? new Object[0] : params;
        if (args.length == 0) {
            Constructor<T> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        }
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            if (constructor.getParameterCount() != args.length) {
                continue;
            }
            if (!matchParameterTypes(constructor.getParameterTypes(), args)) {
                continue;
            }
            constructor.setAccessible(true);
            //noinspection unchecked
            return (T) constructor.newInstance(args);
        }
        throw new NoSuchMethodException("No matching constructor for " + clazz.getName());
    }

    /**
     * 判断实参类型是否与形参声明兼容（null 实参跳过校验）
     */
    private static boolean matchParameterTypes(Class<?>[] paramTypes, Object[] args) {
        for (int i = 0; i < paramTypes.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                continue;
            }
            Class<?> paramType = paramTypes[i];
            Class<?> argType = arg.getClass();
            if (wrap(paramType).isAssignableFrom(argType)) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * 基本类型转为对应的包装类型
     */
    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        return type;
    }

    /**
     * 将 {@link Type} 解析为可加载的 {@link Class}
     */
    private static Class<?> toClass(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            return (Class<?>) parameterizedType.getRawType();
        }
        if (type instanceof WildcardType wildcardType) {
            Type[] upper = wildcardType.getUpperBounds();
            if (upper.length > 0) {
                return toClass(upper[0]);
            }
            return Object.class;
        }
        if (type instanceof GenericArrayType genericArrayType) {
            return Array.newInstance(toClass(genericArrayType.getGenericComponentType()), 0).getClass();
        }
        if (type instanceof TypeVariable<?>) {
            return Object.class;
        }
        throw new IllegalArgumentException("Unsupported type: " + type);
    }

    /**
     * 沿继承链查找并打开访问权限的字段
     */
    private static Field getField0(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    /**
     * 收集当前类及父类上声明的全部字段
     */
    private static Field[] getFields0(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                field.setAccessible(true);
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return fields.toArray(Field[]::new);
    }

    /**
     * 读取字段值（已 setAccessible）
     */
    private static Object getFieldValue0(Object obj, Field field) throws IllegalAccessException {
        field.setAccessible(true);
        return field.get(obj);
    }

    /**
     * 写入字段值（已 setAccessible）
     */
    private static void setFieldValue0(Object obj, Field field, Object value) throws IllegalAccessException {
        field.setAccessible(true);
        field.set(obj, value);
    }

    /** 判断字段类型是否为 boolean 包装类 */
    private static boolean isBooleanField(Class<?> fieldType) {
        return fieldType == boolean.class || fieldType == Boolean.class;
    }

    /**
     * 按 JavaBean 约定查找无参 getter，找不到或不符合约定时返回 null
     */
    private static Method findGetterMethod(Class<?> clazz, String methodName) {
        try {
            Method method = getMethod0(clazz, methodName);
            return isGetterOrSetter(method, false) ? method : null;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    /**
     * 沿继承链查找并打开访问权限的方法
     */
    private static Method getMethod0(Class<?> clazz, String methodName, Class<?>... paramTypes)
            throws NoSuchMethodException {
        Class<?>[] types = paramTypes == null ? new Class<?>[0] : paramTypes;
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Method method = current.getDeclaredMethod(methodName, types);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    /**
     * 判断方法是否符合 JavaBean 的 getter 或 setter 约定
     *
     * @param setter true 校验 setter，false 校验 getter
     */
    private static boolean isGetterOrSetter(Method method, boolean setter) {
        if (method == null || Modifier.isStatic(method.getModifiers())) {
            return false;
        }
        int paramCount = method.getParameterCount();
        String name = method.getName();
        if (setter) {
            return paramCount == 1 && name.startsWith("set") && name.length() > 3
                    && method.getReturnType() == void.class;
        }
        if (paramCount != 0 || method.getReturnType() == void.class) {
            return false;
        }
        if (name.startsWith("get") && name.length() > 3) {
            return true;
        }
        return name.startsWith("is") && name.length() > 2
                && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class);
    }

    /**
     * 调用实例或静态方法（静态方法时 target 为 null）
     */
    private static Object invoke0(Object target, Method method, Object... args) throws ReflectiveOperationException {
        method.setAccessible(true);
        Object[] params = args == null ? new Object[0] : args;
        return method.invoke(target, params);
    }

    /**
     * 获取类上第 index 个泛型实参，找不到时抛异常
     */
    private static Type getTypeArgument(Class<?> clazz, int index) {
        Type type = resolveTypeArgument(clazz, index);
        if (type == null) {
            throw new IllegalArgumentException("Type argument index " + index + " not found on " + clazz.getName());
        }
        return type;
    }

    /**
     * 从父类、接口的 ParameterizedType 中递归解析泛型实参
     */
    private static Type resolveTypeArgument(Type type, int index) {
        if (type == null) {
            return null;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            Type[] actual = parameterizedType.getActualTypeArguments();
            if (index >= 0 && index < actual.length) {
                return actual[index];
            }
            Type raw = parameterizedType.getRawType();
            if (raw instanceof Class<?> rawClass) {
                return resolveTypeArgument(rawClass.getGenericSuperclass(), index);
            }
        }
        if (type instanceof Class<?> clazz) {
            Type superType = clazz.getGenericSuperclass();
            Type fromSuper = resolveTypeArgument(superType, index);
            if (fromSuper != null) {
                return fromSuper;
            }
            for (Type iface : clazz.getGenericInterfaces()) {
                Type fromIface = resolveTypeArgument(iface, index);
                if (fromIface != null) {
                    return fromIface;
                }
            }
        }
        return null;
    }

    /**
     * 获取直接父类或首个泛型接口上的全部类型实参
     */
    private static Type[] getTypeArguments0(Class<?> clazz) {
        Type superType = clazz.getGenericSuperclass();
        if (superType instanceof ParameterizedType parameterizedType) {
            return parameterizedType.getActualTypeArguments();
        }
        for (Type iface : clazz.getGenericInterfaces()) {
            if (iface instanceof ParameterizedType parameterizedType) {
                return parameterizedType.getActualTypeArguments();
            }
        }
        return new Type[0];
    }

}
