package com.fly.sync;

import com.fly.sync.executor.Executor;
import com.fly.sync.setting.Setting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    public final static Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] argv) {

        try {
            Setting.readSettings();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return;
        }

        Executor executor = new Executor();

        try {
            executor.connect();
            executor.run();
            executor.await();
        } catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            //System.exit(0);
        } finally {
            executor.close();
        }

        logger.info("Run exit.");
    }


}