/*
 * Copyright 2017 Huawei Technologies Co., Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicecomb.serviceregistry.registry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import io.servicecomb.config.archaius.sources.MicroserviceConfigLoader;
import io.servicecomb.serviceregistry.ServiceRegistry;
import io.servicecomb.serviceregistry.api.Const;
import io.servicecomb.serviceregistry.api.registry.BasePath;
import io.servicecomb.serviceregistry.api.registry.Microservice;
import io.servicecomb.serviceregistry.api.registry.MicroserviceInstance;
import io.servicecomb.serviceregistry.api.registry.MicroserviceManager;
import io.servicecomb.serviceregistry.cache.InstanceCacheManager;
import io.servicecomb.serviceregistry.cache.InstanceVersionCacheManager;
import io.servicecomb.serviceregistry.client.IpPortManager;
import io.servicecomb.serviceregistry.client.ServiceRegistryClient;
import io.servicecomb.serviceregistry.config.ServiceRegistryConfig;
import io.servicecomb.serviceregistry.task.MicroserviceServiceCenterTask;
import io.servicecomb.serviceregistry.task.ServiceCenterTask;
import io.servicecomb.serviceregistry.task.event.ExceptionEvent;
import io.servicecomb.serviceregistry.task.event.RecoveryEvent;
import io.servicecomb.serviceregistry.task.event.ShutdownEvent;

public abstract class AbstractServiceRegistry implements ServiceRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractServiceRegistry.class);

    protected EventBus eventBus;

    protected MicroserviceManager microserviceManager = new MicroserviceManager();

    protected InstanceCacheManager instanceCacheManager;

    protected IpPortManager ipPortManager;

    protected InstanceVersionCacheManager instanceVersionCacheManager;

    protected ServiceRegistryClient srClient;

    protected ServiceRegistryConfig serviceRegistryConfig;

    protected ServiceCenterTask serviceCenterTask;

    // any exeption event will set cache not avaiable, but not clear cache
    // any recovery event will clear cache
    protected boolean cacheAvaiable;

    public AbstractServiceRegistry(EventBus eventBus, ServiceRegistryConfig serviceRegistryConfig,
            MicroserviceConfigLoader loader) {
        this.eventBus = eventBus;
        this.serviceRegistryConfig = serviceRegistryConfig;

        microserviceManager.init(loader);

        // temp compatible
        //        if (microserviceManager.getMicroservices().size() > 1) {
        //            throw new IllegalArgumentException("only support one microservice.");
        //        }
    }

    @Override
    public void init() {
        instanceCacheManager = new InstanceCacheManager(eventBus, this);
        ipPortManager = new IpPortManager(serviceRegistryConfig, instanceCacheManager);
        instanceVersionCacheManager = new InstanceVersionCacheManager(eventBus, this);
        if (srClient == null) {
            srClient = createServiceRegistryClient();
        }

        createServiceCenterTask();

        eventBus.register(this);
    }

    public MicroserviceManager getMicroserviceManager() {
        return microserviceManager;
    }

    @Override
    public ServiceRegistryClient getServiceRegistryClient() {
        return srClient;
    }

    public void setServiceRegistryClient(ServiceRegistryClient serviceRegistryClient) {
        this.srClient = serviceRegistryClient;
    }

    public IpPortManager getIpPortManager() {
        return ipPortManager;
    }

    @Override
    public InstanceCacheManager getInstanceCacheManager() {
        return instanceCacheManager;
    }

    @Override
    public InstanceVersionCacheManager getInstanceVersionCacheManager() {
        return instanceVersionCacheManager;
    }

    protected abstract ServiceRegistryClient createServiceRegistryClient();

    @Override
    public void run() {
        loadStaticConfiguration();

        // try register
        // if failed, then retry in thread
        serviceCenterTask.init();
    }

    private void loadStaticConfiguration() {
        // TODO 如果yaml定义了paths规则属性，替换默认值，现需要DynamicPropertyFactory支持数组获取
        for (Microservice microservice : microserviceManager.getMicroservices()) {
            List<BasePath> paths = microservice.getPaths();
            for (BasePath path : paths) {
                if (path.getProperty() == null) {
                    path.setProperty(new HashMap<>());
                }
                path.getProperty().put(Const.PATH_CHECKSESSION, "false");
            }
        }
    }

    private void createServiceCenterTask() {
        serviceCenterTask = new ServiceCenterTask(eventBus, serviceRegistryConfig);
        for (Microservice microservice : microserviceManager.getMicroservices()) {
            MicroserviceServiceCenterTask task =
                new MicroserviceServiceCenterTask(eventBus, serviceRegistryConfig, srClient, microservice);
            serviceCenterTask.addMicroserviceTask(task);
        }
    }

    @Subscribe
    public void onException(ExceptionEvent event) {
        cacheAvaiable = false;
    }

    @Subscribe
    public void onRecovered(RecoveryEvent event) {
        if (!cacheAvaiable) {
            cacheAvaiable = true;

            instanceCacheManager.cleanUp();
            ipPortManager.clearInstanceCache();
            instanceVersionCacheManager.cleanUp();
            LOGGER.info(
                    "Reconnected to service center, clean up the provider's microservice instances cache.");
        }
    }

    public boolean unregsiterInstance() {
        for (Microservice microservice : microserviceManager.getMicroservices()) {
            if (!unregsiterInstance(microservice.getIntance())) {
                return false;
            }
        }

        return true;
    }

    public boolean unregsiterInstance(MicroserviceInstance microserviceInstance) {
        boolean result = srClient.unregisterMicroserviceInstance(microserviceInstance.getServiceId(),
                microserviceInstance.getInstanceId());
        if (!result) {
            LOGGER.error("Unregister microservice instance failed. microserviceId={} instanceId={}",
                    microserviceInstance.getServiceId(),
                    microserviceInstance.getInstanceId());
            return false;
        }
        LOGGER.info("Unregister microservice instance success. microserviceId={} instanceId={}",
                microserviceInstance.getServiceId(),
                microserviceInstance.getInstanceId());
        return true;
    }

    public List<MicroserviceInstance> findServiceInstance(String appId, String serviceName,
            String versionRule) {
        // TODO:只能任选本进程中的一个微服务，这会导致依赖关系不准确
        Microservice microservice = microserviceManager.getDefaultMicroserviceForce();
        List<MicroserviceInstance> instances = srClient.findServiceInstance(microservice.getServiceId(),
                appId,
                serviceName,
                versionRule);
        if (instances == null) {
            LOGGER.error("find empty instances from service center. service={}/{}", appId, serviceName);
            return null;
        }

        LOGGER.info("find instances[{}] from service center success. service={}/{}",
                instances.size(),
                appId,
                serviceName);
        for (MicroserviceInstance instance : instances) {
            LOGGER.info("service id={}, instance id={}, endpoints={}",
                    instance.getServiceId(),
                    instance.getInstanceId(),
                    instance.getEndpoints());
        }
        return instances;
    }

    @Override
    public boolean updateMicroserviceProperties(Map<String, String> properties) {
        Microservice microservice = microserviceManager.getDefaultMicroservice();
        return updateMicroserviceProperties(microservice, properties);
    }

    @Override
    public boolean updateMicroserviceProperties(String microserviceName, Map<String, String> properties) {
        Microservice microservice = microserviceManager.ensureFindMicroservice(microserviceName);
        return updateMicroserviceProperties(microservice, properties);
    }

    public boolean updateMicroserviceProperties(Microservice microservice, Map<String, String> properties) {
        boolean success = srClient.updateMicroserviceProperties(microservice.getServiceId(),
                properties);
        if (success) {
            microservice.setProperties(properties);
        }
        return success;
    }

    // update microservice instance properties
    // if there are multiple microservice, then throw exception
    public boolean updateInstanceProperties(Map<String, String> instanceProperties) {
        Microservice microservice = microserviceManager.getDefaultMicroservice();
        return updateInstanceProperties(microservice, instanceProperties);
    }

    public boolean updateInstanceProperties(String microserviceName, Map<String, String> instanceProperties) {
        Microservice microservice = microserviceManager.ensureFindMicroservice(microserviceName);
        return updateInstanceProperties(microservice, instanceProperties);
    }

    public boolean updateInstanceProperties(Microservice microservice,
            Map<String, String> instanceProperties) {
        MicroserviceInstance microserviceInstance = microservice.getIntance();
        boolean success = srClient.updateInstanceProperties(microserviceInstance.getServiceId(),
                microserviceInstance.getInstanceId(),
                instanceProperties);
        if (success) {
            microserviceInstance.setProperties(instanceProperties);
        }
        return success;
    }

    public Microservice getRemoteMicroservice(String microserviceId) {
        return srClient.getMicroservice(microserviceId);
    }

    public Microservice getMicroservice() {
        return microserviceManager.getDefaultMicroservice();
    }

    public MicroserviceInstance getMicroserviceInstance() {
        return microserviceManager.getDefaultMicroserviceInstance();
    }

    public void destory() {
        eventBus.post(new ShutdownEvent());
        unregsiterInstance();
    }
}
