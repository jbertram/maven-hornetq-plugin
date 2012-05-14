/*
* JBoss, Home of Professional Open Source.
* Copyright 2010, Red Hat, Inc., and individual contributors
* as indicated by the @author tags. See the copyright.txt file in the
* distribution for a full listing of individual contributors.
*
* This is free software; you can redistribute it and/or modify it
* under the terms of the GNU Lesser General Public License as
* published by the Free Software Foundation; either version 2.1 of
* the License, or (at your option) any later version.
*
* This software is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
* Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public
* License along with this software; if not, write to the Free
* Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
* 02110-1301 USA, or see the FSF site: http://www.fsf.org.
*/
package org.hornetq.server;

import org.hornetq.core.config.Configuration;
import org.hornetq.core.config.impl.ConfigurationImpl;
import org.hornetq.core.config.impl.FileConfiguration;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.server.JournalType;
import org.hornetq.core.server.NodeManager;
import org.hornetq.core.server.impl.HornetQServerImpl;
import org.hornetq.jms.server.JMSServerManager;
import org.hornetq.jms.server.impl.JMSServerManagerImpl;
import org.hornetq.maven.InVMNodeManagerServer;
import org.hornetq.spi.core.security.HornetQSecurityManagerImpl;
import org.jnp.server.Main;
import org.jnp.server.NamingBeanImpl;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * @author <a href="mailto:andy.taylor@jboss.org">Andy Taylor</a>
 *         5/14/12
 *
 * This will bootstrap the HornetQ Server and also the naming server if required
 */
public class HornetQBootstrap
{
   private Boolean useJndi;

   private String jndiHost;

   private int jndiPort;

   private int jndiRmiPort;

   private String hornetqConfigurationDir;

   private Boolean waitOnStart;

   private String nodeId;

   private static Map<String, NodeManager> managerMap = new HashMap<String, NodeManager>();


   public HornetQBootstrap(Boolean useJndi, String jndiHost, int jndiPort, int jndiRmiPort, String hornetqConfigurationDir, Boolean waitOnStart, String nodeId)
   {
      this.useJndi = useJndi;
      this.jndiHost = jndiHost;
      this.jndiPort = jndiPort;
      this.jndiRmiPort = jndiRmiPort;
      this.hornetqConfigurationDir = hornetqConfigurationDir;
      this.waitOnStart = waitOnStart;
      this.nodeId = nodeId;
   }

   public HornetQBootstrap(String[] args)
   {
      this.useJndi = Boolean.valueOf(args[0]);
      this.jndiHost = args[1];
      this.jndiPort = Integer.valueOf(args[2]);
      this.jndiRmiPort = Integer.valueOf(args[3]);
      this.hornetqConfigurationDir = args[4];
      this.waitOnStart = Boolean.valueOf(args[5]);;
      this.nodeId = args[6];
   }

   public void execute() throws Exception
   {
      try
      {
         final Main main = useJndi?new Main() : null;
         if (useJndi)
         {
            System.setProperty("java.naming.factory.initial", "org.jnp.interfaces.NamingContextFactory");
            System.setProperty("java.naming.factory.url.pkgs", "org.jboss.naming:org.jnp.interfaces");
            NamingBeanImpl namingBean = new NamingBeanImpl();
            namingBean.start();
            main.setNamingInfo(namingBean);
            main.setBindAddress(jndiHost);
            main.setPort(jndiPort);
            main.setRmiBindAddress(jndiHost);
            main.setRmiPort(jndiRmiPort);
            main.start();
         }

         Configuration configuration;
         if (hornetqConfigurationDir != null)
         {
            //extendPluginClasspath(hornetqConfigurationDir);
            configuration = new FileConfiguration();
            File file = new File(hornetqConfigurationDir + "/" + "hornetq-configuration.xml");
            ((FileConfiguration) configuration).setConfigurationUrl(file.toURI().toURL().toExternalForm());
            ((FileConfiguration) configuration).start();
         }
         else
         {
            configuration = new ConfigurationImpl();
            configuration.setJournalType(JournalType.NIO);
         }

         HornetQServer server;

         if(nodeId != null && !nodeId.equals("") && !nodeId.equals("null"))
         {
             InVMNodeManager nodeManager = (InVMNodeManager) managerMap.get(nodeId);
             if(nodeManager == null)
             {
                 nodeManager = new InVMNodeManager();
                 managerMap.put(nodeId, nodeManager);
             }
             server = new InVMNodeManagerServer(configuration, ManagementFactory.getPlatformMBeanServer(), new HornetQSecurityManagerImpl(), nodeManager);
         }
         else
         {
            server = new HornetQServerImpl(configuration, ManagementFactory.getPlatformMBeanServer(), new HornetQSecurityManagerImpl());
         }

         final JMSServerManager manager = new JMSServerManagerImpl(server);
         manager.start();

         if (waitOnStart)
         {
            String dirName = System.getProperty("hornetq.config.dir", ".");
            final File file = new File(dirName + "/STOP_ME");
            if (file.exists())
            {
               file.delete();
            }

            while (!file.exists())
            {
               Thread.sleep(500);
            }

            manager.stop();
            if(main != null)
            {
               main.stop();
            }
            file.delete();
         }
         else
         {
            String dirName = hornetqConfigurationDir != null?hornetqConfigurationDir:".";
            final File stopFile = new File(dirName + "/STOP_ME");
            if (stopFile.exists())
            {
               stopFile.delete();
            }
            final File killFile = new File(dirName + "/KILL_ME");
            if (killFile.exists())
            {
               killFile.delete();
            }
            final Timer timer = new Timer("HornetQ Server Shutdown Timer", true);
            timer.scheduleAtFixedRate(new ServerStopTimerTask(stopFile, killFile, timer, manager, main), 500, 500);
         }
      }
      catch (Exception e)
      {
         e.printStackTrace();
         throw new Exception(e.getMessage());
      }
   }


   private static class ServerStopTimerTask extends TimerTask
   {
      private final File stopFile;
      private final Timer timer;
      private final JMSServerManager manager;
      private final Main main;
      private final File killFile;

      public ServerStopTimerTask(File stopFile, File killFile, Timer timer, JMSServerManager manager, Main main)
      {
         this.stopFile = stopFile;
         this.killFile = killFile;
         this.timer = timer;
         this.manager = manager;
         this.main = main;
      }

      @Override
      public void run()
      {
         if (stopFile.exists())
         {
            try
            {
               timer.cancel();
            } finally
            {
               try
               {
                  manager.stop();
                  if (main != null)
                  {
                     main.stop();
                  }
                  stopFile.delete();
               } catch (Exception e)
               {
                  e.printStackTrace();
               }
            }
         }
         else if(killFile.exists())
         {
            try
            {
               manager.getHornetQServer().stop(true);
            }
            catch (Exception e)
            {
               e.printStackTrace();
            }
         }
      }
   }
}
