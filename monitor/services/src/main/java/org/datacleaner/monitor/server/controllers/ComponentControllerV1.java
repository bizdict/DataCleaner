/**
 * DataCleaner (community edition)
 * Copyright (C) 2014 Neopost - Customer Information Management
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.datacleaner.monitor.server.controllers;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.datacleaner.configuration.DataCleanerConfiguration;
import org.datacleaner.descriptors.AbstractPropertyDescriptor;
import org.datacleaner.descriptors.ComponentDescriptor;
import org.datacleaner.descriptors.ConfiguredPropertyDescriptor;
import org.datacleaner.descriptors.TransformerDescriptor;
import org.datacleaner.desktop.api.HiddenProperty;
import org.datacleaner.monitor.configuration.ComponentCache;
import org.datacleaner.monitor.configuration.ComponentCacheConfigWrapper;
import org.datacleaner.monitor.configuration.ComponentCacheMapImpl;
import org.datacleaner.monitor.configuration.ComponentHandlerFactory;
import org.datacleaner.monitor.configuration.ComponentStoreHolder;
import org.datacleaner.monitor.configuration.TenantContext;
import org.datacleaner.monitor.configuration.TenantContextFactory;
import org.datacleaner.monitor.server.components.ComponentHandler;
import org.datacleaner.restclient.ComponentController;
import org.datacleaner.restclient.ComponentList;
import org.datacleaner.restclient.ComponentNotFoundException;
import org.datacleaner.restclient.CreateInput;
import org.datacleaner.restclient.ProcessInput;
import org.datacleaner.restclient.ProcessOutput;
import org.datacleaner.restclient.ProcessResult;
import org.datacleaner.restclient.ProcessStatelessInput;
import org.datacleaner.restclient.ProcessStatelessOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.util.UriUtils;

/**
 * Controller for DataCleaner components (transformers and analyzers). It enables to use a particular component
 * and provide the input data separately without any need of the whole job or datastore configuration.
 * @since 8. 7. 2015
 */
@Controller
@RequestMapping("/{tenant}/components")
public class ComponentControllerV1 implements ComponentController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ComponentControllerV1.class);
    private ComponentCache _componentCache = null;
    private static final String PARAMETER_NAME_TENANT = "tenant";
    private static final String PARAMETER_NAME_ID = "id";
    private static final String PARAMETER_NAME_NAME = "name";

    @Autowired
    TenantContextFactory _tenantContextFactory;


    @PostConstruct
    public void init() {
        _componentCache = new ComponentCacheMapImpl(_tenantContextFactory);
    }

    @PreDestroy
    public void close() throws InterruptedException {
        _componentCache.close();
    }

    /**
     * It returns a list of all components and their configurations.
     * @param tenant
     * @return
     */
    @ResponseBody
    @RequestMapping(method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ComponentList getAllComponents(@PathVariable(PARAMETER_NAME_TENANT) final String tenant) {
        DataCleanerConfiguration configuration = _tenantContextFactory.getContext(tenant).getConfiguration();
        Collection<TransformerDescriptor<?>> transformerDescriptors = configuration.getEnvironment()
                .getDescriptorProvider()
                .getTransformerDescriptors();
        ComponentList componentList = new ComponentList();

        for (TransformerDescriptor descriptor : transformerDescriptors) {
            componentList.add(createComponentInfo(tenant, descriptor));
        }

        return componentList;
    }

    @ResponseBody
    @RequestMapping(value = "/{name}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ComponentList.ComponentInfo getComponentInfo(
            @PathVariable(PARAMETER_NAME_TENANT) final String tenant,
            @PathVariable("name") String name) {
        name = unURLify(name);
        LOGGER.debug("Informing about '" + name + "'");
        DataCleanerConfiguration dcConfig = _tenantContextFactory.getContext(tenant).getConfiguration();
        ComponentDescriptor descriptor = dcConfig.getEnvironment().getDescriptorProvider().getTransformerDescriptorByDisplayName(name);
        return createComponentInfo(tenant, descriptor);
    }


    /**
     * It creates a new component with the provided configuration, runs it and returns the result.
     * @param tenant
     * @param name
     * @param processStatelessInput
     * @return
     */
    @ResponseBody
    @RequestMapping(value = "/{name}", method = RequestMethod.PUT, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ProcessStatelessOutput processStateless(
            @PathVariable(PARAMETER_NAME_TENANT) final String tenant,
            @PathVariable(PARAMETER_NAME_NAME) final String name,
            @RequestBody final ProcessStatelessInput processStatelessInput) {
        String decodedName = unURLify(name);
        LOGGER.debug("Running '" + decodedName + "'");
        TenantContext tenantContext = _tenantContextFactory.getContext(tenant);
        ComponentHandler handler =  ComponentHandlerFactory.createComponent(tenantContext, decodedName, processStatelessInput.configuration);
        ProcessStatelessOutput output = new ProcessStatelessOutput();
        output.rows = handler.runComponent(processStatelessInput.data);
        output.result = handler.closeComponent();
        return output;
    }

    /**
     * It runs the component and returns the results.
     */
    @ResponseBody
    @RequestMapping(value = "/{name}", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public String createComponent(
            @PathVariable(PARAMETER_NAME_TENANT) final String tenant,
            @PathVariable(PARAMETER_NAME_NAME) final String name,              //1 day
            @RequestParam(value = "timeout", required = false, defaultValue = "86400000") final String timeout,
            @RequestBody final CreateInput createInput) {
        String decodedName = unURLify(name);
        TenantContext tenantContext = _tenantContextFactory.getContext(tenant);
        String id = UUID.randomUUID().toString();
        long longTimeout = Long.parseLong(timeout);
        _componentCache.put(
                tenant,
                tenantContext,
                new ComponentStoreHolder(longTimeout, createInput, id, decodedName)
        );
        return id;
    }

    /**
     * It returns the continuous result of the component for the provided input data.
     */
    @ResponseBody
    @RequestMapping(value = "/_instance/{id}", method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ProcessOutput processComponent(
            @PathVariable(PARAMETER_NAME_TENANT) final String tenant,
            @PathVariable(PARAMETER_NAME_ID) final String id,
            @RequestBody final ProcessInput processInput)
            throws ComponentNotFoundException {
        TenantContext tenantContext = _tenantContextFactory.getContext(tenant);
        ComponentCacheConfigWrapper config = _componentCache.get(id, tenant, tenantContext);
        if(config == null){
                LOGGER.warn("Component with id {} does not exist.", id);
                throw ComponentNotFoundException.createInstanceNotFound(id);
            }
        ComponentHandler handler = config.getHandler();
        ProcessOutput out = new ProcessOutput();
        out.rows = handler.runComponent(processInput.data);
        return out;
    }

    /**
     * It returns the component's final result.
     */
    @ResponseBody
    @RequestMapping(value = "/{id}/result", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ProcessResult getFinalResult(
            @PathVariable(PARAMETER_NAME_TENANT) final String tenant,
            @PathVariable(PARAMETER_NAME_ID) final String id)
            throws ComponentNotFoundException {
        // TODO - only for analyzers, implement it later after the architecture
        // decisions regarding the load-balancing and failover.
        return null;
    }

    /**
     * It deletes the component.
     */
    @ResponseBody
    @RequestMapping(value = "/{id}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public void deleteComponent(
            @PathVariable(PARAMETER_NAME_TENANT) final String tenant,
            @PathVariable(PARAMETER_NAME_ID) final String id)
            throws ComponentNotFoundException {
        TenantContext tenantContext = _tenantContextFactory.getContext(tenant);
        boolean isHere = _componentCache.remove(id, tenantContext);
        if (!isHere) {
            LOGGER.warn("Instance of component {} not found in the cache and in the store", id);
            throw ComponentNotFoundException.createInstanceNotFound(id);
        }
    }

    private String unURLify(String url) {
        return url.replace("_@_", "/");
    }

    public static ComponentList.ComponentInfo createComponentInfo(String tenant, ComponentDescriptor descriptor) {
        return new ComponentList.ComponentInfo()
                .setName(descriptor.getDisplayName())
                .setDescription(descriptor.getDescription())
                .setCreateURL(getURLForCreation(tenant, descriptor))
                .setProperties(createPropertiesInfo(descriptor));
    }

    static private String getURLForCreation(String tenant, ComponentDescriptor descriptor) {
        try {
            return String.format(
                    "/repository/%s/components/%s",
                    UriUtils.encodePathSegment(tenant, "UTF8"),
                    UriUtils.encodePathSegment(descriptor.getDisplayName().replace("/", "_@_"), "UTF8"));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    static Map<String, ComponentList.PropertyInfo> createPropertiesInfo(ComponentDescriptor descriptor) {
        Map<String, ComponentList.PropertyInfo> result = new HashMap<>();
        for (ConfiguredPropertyDescriptor propertyDescriptor : (Set<ConfiguredPropertyDescriptor>) descriptor.getConfiguredProperties()) {
            if (propertyDescriptor.getAnnotation(HiddenProperty.class) != null) {
                continue;
            }

            ComponentList.PropertyInfo propInfo = new ComponentList.PropertyInfo();
            propInfo.setName(propertyDescriptor.getName());
            propInfo.setDescription(propertyDescriptor.getDescription());
            propInfo.setRequired(propertyDescriptor.isRequired());
            propInfo.setIsInputColumn(propertyDescriptor.isInputColumn());
            propInfo.setType(getPropertyType(descriptor, propertyDescriptor));
            if(propertyDescriptor.getBaseType().isEnum()) {
                propInfo.setEnumValues(toStringArray(propertyDescriptor.getBaseType().getEnumConstants()));
            }
            result.put(propInfo.getName(), propInfo);
        }
        return result;
    }

    static String[] toStringArray(Object[] array) {
        String[] result = new String[array.length];
        for(int i = 0; i < array.length; i++) {
            result[i] = String.valueOf(array[i]);
        }
        return result;
    }

    static String getPropertyType(ComponentDescriptor descriptor, ConfiguredPropertyDescriptor propertyDescriptor) {
        // TODO: move the "getField" to ComponentDescriptor interface to avoid retyping
        if(propertyDescriptor instanceof AbstractPropertyDescriptor) {
            Field f = ((AbstractPropertyDescriptor)propertyDescriptor).getField();
            Type t = f.getGenericType();
            if(t instanceof Class) {
                return ((Class) t).getCanonicalName();
            } else {
                return f.getGenericType().toString();
            }
        } else {
            return propertyDescriptor.getType().getCanonicalName();
        }
    }


}
