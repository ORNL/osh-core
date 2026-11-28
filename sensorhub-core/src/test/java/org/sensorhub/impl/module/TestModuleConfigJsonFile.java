/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.module;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.api.database.DatabaseConfig;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.processing.ProcessConfig;
import org.sensorhub.api.sensor.SensorConfig;
import org.sensorhub.api.service.ServiceConfig;
import static org.junit.Assert.*;


public class TestModuleConfigJsonFile
{
    ModuleConfigJsonFile configDb;
    File configFile;
    
    
    @Before
    public void setup() throws Exception
    {
        configFile = new File("test-conf.json");
        configFile.deleteOnExit();
        
        FileUtils.copyURLToFile(TestModuleConfigJsonFile.class.getResource("/module-config.json"), configFile);        
        configDb = new ModuleConfigJsonFile(configFile.getAbsolutePath(), false);
    }
    
    
    @Test
    public void testAdd() throws Exception
    {
        SensorConfig config1 = new SensorConfig();
        config1.id = UUID.randomUUID().toString();
        config1.name = "Sensor1";
        config1.moduleClass = "org.sensorhub.sensor.***";
        configDb.add(config1);
        
        displayFile();
    }
    
    
    @Test(expected=RuntimeException.class)
    public void testAddAndRemove() throws Exception
    {
        SensorConfig config1 = new SensorConfig();
        config1.id = UUID.randomUUID().toString();
        config1.name = "Sensor1";
        config1.moduleClass = "org.sensorhub.sensor.***";
        configDb.add(config1);
        
        displayFile();
        
        configDb.remove(config1.id);
        configDb.get(config1.id);
    }
    
    
    @Test
    public void testAddAndReadBack() throws Exception
    {
        ModuleConfig storedConf;
        
        ProcessConfig config1 = new ProcessConfig();
        config1.id = UUID.randomUUID().toString();
        config1.name = "Process1";
        config1.moduleClass = "org.sensorhub.process.ProcessModel";
        configDb.add(config1);
        
        ServiceConfig config2 = new ServiceConfig();
        config2.id = UUID.randomUUID().toString();
        config2.name = "Service1";
        config2.moduleClass = "org.sensorhub.service.SosService";
        configDb.add(config2);
        
        ServiceConfig config3 = new ServiceConfig();
        config3.id = UUID.randomUUID().toString();
        config3.name = "Service2";
        config3.moduleClass = "org.sensorhub.service.SpsService";
        configDb.add(config3);
        
        DatabaseConfig config4 = new DatabaseConfig();
        config4.id = UUID.randomUUID().toString();
        config4.name = "DB1";
        config4.moduleClass = "org.sensorhub.persistence.FeatureStorage";
        configDb.add(config4);
        
        // read back
        configDb.commit();
        configDb.close();        
        configDb = new ModuleConfigJsonFile(configFile.getAbsolutePath(), false);
        
        storedConf = configDb.get(config1.id);
        assertTrue(storedConf.id.equals(config1.id));
        assertTrue(storedConf.name.equals(config1.name));
        assertTrue(storedConf.moduleClass.equals(config1.moduleClass));
        
        storedConf = configDb.get(config2.id);
        assertTrue(storedConf.id.equals(config2.id));
        assertTrue(storedConf.name.equals(config2.name));
        assertTrue(storedConf.moduleClass.equals(config2.moduleClass));
        
        storedConf = configDb.get(config3.id);
        assertTrue(storedConf.id.equals(config3.id));
        assertTrue(storedConf.name.equals(config3.name));
        assertTrue(storedConf.moduleClass.equals(config3.moduleClass));
        
        storedConf = configDb.get(config4.id);
        assertTrue(storedConf.id.equals(config4.id));
        assertTrue(storedConf.name.equals(config4.name));
        assertTrue(storedConf.moduleClass.equals(config4.moduleClass));
    }
    
    
    @Test
    public void testRemoveAndReadBack() throws Exception
    {
        ProcessConfig config1 = new ProcessConfig();
        config1.id = UUID.randomUUID().toString();
        config1.name = "Process1";
        config1.moduleClass = "org.sensorhub.process.ProcessModel";
        configDb.add(config1);
        
        ServiceConfig config2 = new ServiceConfig();
        config2.id = UUID.randomUUID().toString();
        config2.name = "Service1";
        config2.moduleClass = "org.sensorhub.service.SosService";
        configDb.add(config2);
        
        assertTrue(configDb.contains(config1.id));
        assertTrue(configDb.contains(config2.id));
        
        // remove one config
        configDb.remove(config1.id);
        
        // read back and check it's not there
        configDb.close();
        configDb = new ModuleConfigJsonFile(configFile.getAbsolutePath(), false);
        
        assertFalse(configDb.contains(config1.id));
    }


    @Test
    public void testCommitPrunesOldTimestampedBackupsAndPreservesManualBackups() throws Exception
    {
        Path dir = Files.createTempDirectory("osh-config-rotation-");
        try
        {
            File isolatedConfig = dir.resolve("config.json").toFile();
            FileUtils.copyURLToFile(TestModuleConfigJsonFile.class.getResource("/module-config.json"), isolatedConfig);

            for (int i = 1; i <= 30; i++)
            {
                String stamp = String.format("202501010000%02d", i);
                Files.writeString(dir.resolve("config.json.bak." + stamp), "[]");
            }
            Path manualPre = dir.resolve("config.json.bak.pre26");
            Path manualNamed = dir.resolve("config.json.bak.golden");
            Path unrelated = dir.resolve("other.json.bak.20250101000001");
            Files.writeString(manualPre, "[]");
            Files.writeString(manualNamed, "[]");
            Files.writeString(unrelated, "[]");

            ModuleConfigJsonFile rotatingDb = new ModuleConfigJsonFile(isolatedConfig.getAbsolutePath(), true);
            ProcessConfig cfg = new ProcessConfig();
            cfg.id = UUID.randomUUID().toString();
            cfg.name = "RotationTestModule";
            cfg.moduleClass = "org.sensorhub.process.ProcessModel";
            rotatingDb.add(cfg);
            rotatingDb.commit();

            List<String> timestamped;
            try (Stream<Path> entries = Files.list(dir))
            {
                timestamped = entries
                        .map(p -> p.getFileName().toString())
                        .filter(name -> name.matches("^config\\.json\\.bak\\.\\d{14}$"))
                        .sorted()
                        .collect(Collectors.toList());
            }

            assertEquals("Timestamped backups must be capped at DEFAULT_MAX_BACKUPS",
                    ModuleConfigJsonFile.DEFAULT_MAX_BACKUPS, timestamped.size());
            assertFalse("Oldest timestamped backups must be pruned",
                    timestamped.contains("config.json.bak.20250101000001"));
            assertFalse("Sixth oldest timestamped backup must be pruned once new commit rotates in",
                    timestamped.contains("config.json.bak.20250101000006"));
            assertTrue("Newer timestamped backup must be retained",
                    timestamped.contains("config.json.bak.20250101000007"));
            assertTrue("Manual pre-upgrade backup must never be pruned", Files.exists(manualPre));
            assertTrue("Manual named backup must never be pruned", Files.exists(manualNamed));
            assertTrue("Unrelated config backup must never be pruned", Files.exists(unrelated));

            ModuleConfigJsonFile reloaded = new ModuleConfigJsonFile(isolatedConfig.getAbsolutePath(), false);
            assertTrue(reloaded.contains(cfg.id));
        }
        finally
        {
            FileUtils.deleteDirectory(dir.toFile());
        }
    }


    @Test
    public void testCommitHonorsMaxBackupsSystemProperty() throws Exception
    {
        Path dir = Files.createTempDirectory("osh-config-rotation-prop-");
        String previous = System.getProperty(ModuleConfigJsonFile.MAX_BACKUPS_PROPERTY);
        try
        {
            System.setProperty(ModuleConfigJsonFile.MAX_BACKUPS_PROPERTY, "3");
            File isolatedConfig = dir.resolve("config.json").toFile();
            FileUtils.copyURLToFile(TestModuleConfigJsonFile.class.getResource("/module-config.json"), isolatedConfig);
            for (int i = 1; i <= 5; i++)
            {
                Files.writeString(dir.resolve(String.format("config.json.bak.202501010000%02d", i)), "[]");
            }

            ModuleConfigJsonFile rotatingDb = new ModuleConfigJsonFile(isolatedConfig.getAbsolutePath(), true);
            rotatingDb.commit();

            List<String> timestamped;
            try (Stream<Path> entries = Files.list(dir))
            {
                timestamped = entries
                        .map(p -> p.getFileName().toString())
                        .filter(name -> name.matches("^config\\.json\\.bak\\.\\d{14}$"))
                        .sorted()
                        .collect(Collectors.toList());
            }
            assertEquals(3, timestamped.size());
            assertFalse(timestamped.contains("config.json.bak.20250101000003"));
            assertTrue(timestamped.contains("config.json.bak.20250101000004"));
            assertTrue(timestamped.contains("config.json.bak.20250101000005"));
        }
        finally
        {
            if (previous == null)
                System.clearProperty(ModuleConfigJsonFile.MAX_BACKUPS_PROPERTY);
            else
                System.setProperty(ModuleConfigJsonFile.MAX_BACKUPS_PROPERTY, previous);
            FileUtils.deleteDirectory(dir.toFile());
        }
    }
    
    
    private void displayFile() throws Exception
    {
        // print out file
        BufferedReader reader = new BufferedReader(new FileReader(configFile));
        String line;
        while ((line = reader.readLine()) != null)
            System.out.println(line);
        reader.close();
    }
    
    
    @After
    public void cleanup()
    {
        if (configFile != null)
            configFile.delete();
    }
}
