package com.seismicgames.spark_annotations;

import com.google.gson.Gson;
import com.seismicgames.spark_annotations.annotations.Controller;
import com.seismicgames.spark_annotations.annotations.Filter;
import com.seismicgames.spark_annotations.annotations.Route;
import com.seismicgames.spark_annotations.annotations.SparkWebSocket;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.reflections.Reflections;
import org.reflections.scanners.MethodAnnotationsScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import com.seismicgames.spark_annotations.exceptions.RouteException;
import com.seismicgames.spark_annotations.handlers.WebSocketHandler;

import spark.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class Setup {
    private static class BackupTemplateEngine extends TemplateEngine {
        @Override
        public String render(ModelAndView modelAndView) {
            LOGGER.error("Something went wrong, can't render to the registered TemplateEngine");
            return new Gson().toJson(modelAndView.getModel());
        }
    }

    // const
    private static final Logger LOGGER = LogManager.getLogger(Setup.class);

    // static parameters
    private static boolean initialized = false;
    private static String controllerPackage;
    private static String filterPackage;
    private static String wsPackage;
    private static Class<? extends TemplateEngine> templateEngine;
    private static String mainTemplate;

    // for when you want to search your entire package for controllers, Shofilters and websockets
    public static synchronized void Init(String packageName, int maxThreads, int minThreads, int timeout,
                                         Class<? extends TemplateEngine> templateEngine, String mainTemplate) {
        Setup.Init(packageName, packageName, packageName, maxThreads, minThreads, timeout, templateEngine, mainTemplate);
    }

    public static synchronized void Init(String controllerPackage, String filterPackage, String wsPackage,
                                         int maxThreads, int minThreads, int timeout,
                                         Class<? extends TemplateEngine> templateEngine, String mainTemplate) {
        if(initialized) {
            return;
        }

        Setup.controllerPackage = controllerPackage;
        Setup.filterPackage = filterPackage;
        Setup.wsPackage = wsPackage;
        Setup.templateEngine = templateEngine;
        Setup.mainTemplate = mainTemplate;

        // set up the server
        Spark.threadPool(maxThreads, minThreads, timeout);

        // set up static files
        Spark.staticFiles.location("/public");

        // set up 500 error handling
        Spark.internalServerError(Setup::internalErrorRender);
        Spark.notFound(Setup::notFoundErrorRender);

        // set up exception catching
        Spark.exception(RouteException.class, Setup::handleError);
        Spark.exception(InvocationTargetException.class, (exception, request, response) -> {
            InvocationTargetException ex = (InvocationTargetException) exception;
            handleError(ex.getCause(), request, response);
        });

        // set up web socket handlers
        wsSetup();

        // set up the filters
        filterSetup();

        // set up the routes
        routeSetup();

        Spark.init();
        LOGGER.debug("Started cps server...");

        initialized = true;
    }

    // routes
    private static void routeSetup() {
        LOGGER.debug("Setting up routes");
        Reflections reflections = new Reflections(controllerPackage);
        Set<Class<?>> classes = reflections.getTypesAnnotatedWith(Controller.class);

        for (Class clazz : classes) {
            LOGGER.debug("Adding controller {}", clazz.getSimpleName());

            // get class path
            Controller controller = (Controller) clazz.getAnnotation(Controller.class);
            String path = controller.path();
            if (path.endsWith("/")) {
                path = path.substring(0, -1);
            }

            for (Method method : clazz.getMethods()) {
                if (!method.isAnnotationPresent(Route.class)) {
                    continue;
                }

                if (!isValidRouteMethod(clazz, method)) {
                    continue;
                }

                Object obj;
                try {
                    obj = clazz.newInstance();
                } catch (InstantiationException | IllegalAccessException e) {
                    LOGGER.error("Couldn't create controller {}", clazz.getSimpleName());
                    LOGGER.error(e);
                    continue;
                }

                Route route = method.getAnnotation(Route.class);
                final String fullPath = path + route.path();

                LOGGER.debug("Adding route: {}, path: {} method: {}", method.getName(), path, route.method());

                TemplateEngine engine;
                try {
                    engine = templateEngine.newInstance();
                } catch (InstantiationException | IllegalAccessException e) {
                    LOGGER.error("Couldn't instantiate Template Engine ");
                    engine = new BackupTemplateEngine();
                }

                switch (route.method()) {
                    case get:
                        Spark.get(fullPath, (request, response) -> {
                                LOGGER.debug("GET - path: {}", fullPath);
                                return new ModelAndView(method.invoke(obj, request, response), route.template());
                            }, engine
                        );
                        break;
                    case post:
                        Spark.post(fullPath, (request, response) -> {
                                LOGGER.debug("POST - path: {}", fullPath);
                                return new ModelAndView(method.invoke(obj, request, response), route.template());
                            }, engine
                        );
                        break;
                    case put:
                        Spark.put(fullPath, (request, response) -> {
                                LOGGER.debug("PUT - path: {}", fullPath);
                                return new ModelAndView(method.invoke(obj, request, response), route.template());
                            }, engine
                        );
                        break;
                    case delete:
                        Spark.delete(fullPath, (request, response) -> {
                                LOGGER.debug("DELETE - path: {}", fullPath);
                                return new ModelAndView(method.invoke(obj, request, response), route.template());
                            }, engine
                        );
                        break;
                    case options:
                        // TODO: do we need this?
                        Spark.options(fullPath, (request, response) -> {
                                LOGGER.debug("OPTIONS - path: {}", fullPath);
                                return new ModelAndView(method.invoke(obj, request, response), route.template());
                            }, engine
                        );
                        break;
                }
            }
        }
        LOGGER.debug("Finished setting up routes");
    }

    private static boolean isValidRouteMethod(Class clazz, Method method) {
        // TODO: validate controller class ?

        // validate controller methods
        Class[] classes = method.getParameterTypes();

        if (!classes[0].equals(Request.class)) {
            LOGGER.warn("Couldn't register method {} for controller {}, invalid first parameter", method.getName(), clazz.getSimpleName());
            return false;
        }

        if (!classes[1].equals(Response.class)) {
            LOGGER.warn("Couldn't register method {} for controller {}, invalid second parameter", method.getName(), clazz.getSimpleName());
            return false;
        }

        if (!method.getReturnType().equals(Map.class)) {
            LOGGER.warn("Couldn't register method {} for controller {}, invalid return type", method.getName(), clazz.getSimpleName());
            return false;
        }

        return true;
    }
    // end routes

    // filters
    private static void filterSetup() {
        LOGGER.debug("Setting up filters");
        Reflections reflections = new Reflections(filterPackage, new MethodAnnotationsScanner());

        Set<Method> methods = reflections.getMethodsAnnotatedWith(Filter.class);
        for(Method method : methods) {
            if(!Setup.isValidFilterMethod(method.getDeclaringClass(), method)) {
                continue;
            }

            Object obj;
            try {
                obj = method.getDeclaringClass().newInstance();
            } catch (InstantiationException | IllegalAccessException e) {
                LOGGER.error("Couldn't create filter class {}", method.getDeclaringClass().getSimpleName());
                LOGGER.error(e);
                continue;
            }

            Filter filter = method.getAnnotation(com.seismicgames.spark_annotations.annotations.Filter.class);

            LOGGER.debug("Adding filter: {}, class: {} when: {}", method.getName(),
                    method.getDeclaringClass().getSimpleName(), filter.when());

            switch (filter.when()) {
                case before:
                    Spark.before((request, response) -> method.invoke(obj, request, response));
                    break;
                case after:
                    Spark.after((request, response) -> method.invoke(obj, request, response));
                    break;
            }
        }

        LOGGER.debug("Finished setting up filters");
    }

    private static boolean isValidFilterMethod(Class clazz, Method method) {
        // validate controller methods
        Class[] classes = method.getParameterTypes();

        if (!classes[0].equals(Request.class)) {
            LOGGER.warn("Couldn't register method {} for filter {}, invalid first parameter", method.getName(), clazz.getSimpleName());
            return false;
        }

        if (!classes[1].equals(Response.class)) {
            LOGGER.warn("Couldn't register method {} for filter {}, invalid second parameter", method.getName(), clazz.getSimpleName());
            return false;
        }

        return true;
    }
    // end filters

    // websockets
    private static void wsSetup() {
        LOGGER.debug("Setting up websocket handlers");
        Reflections reflections = new Reflections(wsPackage, new MethodAnnotationsScanner(),
                new TypeAnnotationsScanner(), new SubTypesScanner());
        Set<Class<?>> classes = reflections.getTypesAnnotatedWith(SparkWebSocket.class);

        for (Class clazz : classes) {
            if(!clazz.isAnnotationPresent(WebSocket.class)) {
                //websocket needs to have both
                continue;
            }

            SparkWebSocket sws = (SparkWebSocket) clazz.getAnnotation(SparkWebSocket.class);
            LOGGER.debug("Adding websocket handler {}", clazz.getSimpleName());

            if(!Setup.isValidWebSocketClass(clazz)) {
                continue;
            }

            WebSocketHandler.IWebSocket obj;
            try {
                obj = (WebSocketHandler.IWebSocket) clazz.newInstance();
            } catch (InstantiationException | IllegalAccessException e) {
                LOGGER.error("Couldn't create websocket class {}", clazz.getSimpleName());
                LOGGER.error(e);
                continue;
            }

            WebSocketHandler.getInstance().addHandler(obj);
            Spark.webSocket(sws.path(), obj);
        }
    }

    private static boolean isValidWebSocketClass(Class clazz)
    {
        return WebSocketHandler.IWebSocket.class.isAssignableFrom(clazz);
    }
    // end websockets

    // error handlers
    private static String internalErrorRender(Request request, Response response) {
        TemplateEngine engine;
        try {
            engine = templateEngine.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            LOGGER.error("Couldn't instantiate Template Engine ", e);
            engine = new BackupTemplateEngine();
        }

        HashMap<String , String> map = new HashMap<>();
        map.put("error", "true");
        map.put("errorMsg", "There as an error on the server");
        map.put("code", "500");

        return engine.render(new ModelAndView(map, Setup.mainTemplate));
    }

    private static String notFoundErrorRender(Request request, Response response) {
        TemplateEngine engine;
        try {
            engine = templateEngine.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            LOGGER.error("Couldn't instantiate Template Engine ", e);
            engine = new BackupTemplateEngine();
        }

        HashMap<String, String> map = new HashMap<>();
        map.put("error", "true");
        map.put("errorMsg", "The page you were looking for was not found");
        map.put("code", "404");

        return engine.render(new ModelAndView(map, Setup.mainTemplate));
    }

    private static void handleError(Throwable exception, Request request, Response response) {
        RouteException re = (RouteException) exception;
        TemplateEngine engine;
        try {
            engine = templateEngine.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            LOGGER.error("Couldn't instantiate Template Engine ", e);
            engine = new BackupTemplateEngine();
        }

        HashMap<String, String> map = new HashMap<>();

        map.put("error", "true");
        map.put("errorMsg",re.getMessage());
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement st : re.getStackTrace()) {
            sb.append(st.toString()).append("<br />");
        }
        map.put("errorStack", sb.toString());
        map.put("code", Integer.toString(re.code));

        response.status(re.code);
        response.body(engine.render(new ModelAndView(map, Setup.mainTemplate)));
    }
    // end error handlers
}