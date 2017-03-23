package com.seismicgames.spark_annotations.annotations;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Route {
    enum Method {
        get,
        post,
        put,
        delete,
        options
    }

    String path();
    Method method() default Method.get;
    String template();
}
