package org.genomenexus.vep_wrapper;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

public class SystemProcessManagerShutdownBean implements ServletContextListener {
 
    @Override
    public void contextDestroyed(ServletContextEvent event) {
        SystemProcessManager.requestReaperShutdown();
        // give the reaper max 20 seconds to shut down
        int waitPeriodCount = 20;
        while (SystemProcessManager.reaperIsRunning()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
            waitPeriodCount = waitPeriodCount - 1;
            if (waitPeriodCount == 0) {
                return;
            }
        }
    }
 
    @Override
    public void contextInitialized(ServletContextEvent event) {
    }
}
