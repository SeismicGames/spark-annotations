package com.seismicgames.spark_annotations.exceptions;

public class RouteException extends Exception {
    public int code;

    public RouteException(int code) {
        this.code = code;
    }

    public RouteException(int code, String message) {
        super(message);
        this.code = code;
    }

    public RouteException(int code, Throwable cause) {
        super(cause);
        this.code = code;
    }
}
