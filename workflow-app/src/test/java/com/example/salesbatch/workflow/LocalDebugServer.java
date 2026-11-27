package com.example.salesbatch.workflow;

import javax.servlet.MultipartConfigElement;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import com.example.salesbatch.workflow.config.AppConfig;
import com.example.salesbatch.workflow.config.WebConfig;

/**
 * IDE-only entry point (same as WorkflowServer). By default it uses real HBase (docker/local) and runs the
 * Scala job in a local child JVM. Pass -Ddebug.inmemory=true to use the in-memory fakes instead.
 */
public class LocalDebugServer {

    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("server.port", 8081);
        System.setProperty("report.dir", System.getProperty("report.dir", "reports"));
        if (!Boolean.getBoolean("debug.inmemory")) {
            // Keep Activiti runs and pending background jobs in ./data so they survive a restart of this server.
            System.setProperty("activiti.jdbc.url",
                    System.getProperty("activiti.jdbc.url", "jdbc:h2:file:./data/activiti;DB_CLOSE_DELAY=-1"));
        }

        AnnotationConfigWebApplicationContext root = new AnnotationConfigWebApplicationContext();
        root.register(AppConfig.class, Boolean.getBoolean("debug.inmemory") ? TestInfraConfig.class : LocalInfraConfig.class);

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
        server.start();
        System.out.println("Local debug server on http://localhost:" + port + "/");
        server.join();
    }
}
