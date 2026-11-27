package com.example.salesbatch.workflow;

import javax.servlet.MultipartConfigElement;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import com.example.salesbatch.workflow.config.AppConfig;
import com.example.salesbatch.workflow.config.InfraConfig;
import com.example.salesbatch.workflow.config.WebConfig;

/** Starts embedded Jetty with the Spring root context (engine, delegates) and the MVC servlet. */
public class WorkflowServer {

    public static void main(String[] args) throws Exception {
        Server server = createServer(Integer.getInteger("server.port", 8080), AppConfig.class, InfraConfig.class);
        server.start();
        server.join();
    }

    /**
     * Builds, but does not start, the server. The root context is made of the given configuration classes
     * (the real app uses AppConfig and InfraConfig; tests swap InfraConfig for fakes).
     */
    public static Server createServer(int port, Class<?>... rootConfigs) {
        AnnotationConfigWebApplicationContext root = new AnnotationConfigWebApplicationContext();
        root.register(rootConfigs);

        AnnotationConfigWebApplicationContext web = new AnnotationConfigWebApplicationContext();
        web.register(WebConfig.class);

        ServletContextHandler handler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        handler.setContextPath("/");
        handler.addEventListener(new ContextLoaderListener(root));
        ServletHolder dispatcher = new ServletHolder(new DispatcherServlet(web));
        dispatcher.getRegistration().setMultipartConfig(new MultipartConfigElement(System.getProperty("java.io.tmpdir")));
        handler.addServlet(dispatcher, "/*");

        Server server = new Server(port);
        server.setHandler(handler);
        return server;
    }
}
