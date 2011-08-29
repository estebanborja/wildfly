/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
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

package org.jboss.as.messaging;

import org.jboss.as.controller.PathAddress;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.hornetq.core.config.BroadcastGroupConfiguration;
import org.hornetq.core.config.Configuration;
import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.ServiceVerificationHandler;
import org.jboss.as.controller.descriptions.DescriptionProvider;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SOCKET_BINDING;
import org.jboss.as.network.NetworkInterfaceBinding;
import org.jboss.as.network.SocketBinding;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceRegistry;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.value.ImmediateValue;

/**
 * Handler for adding a broadcast group.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class BroadcastGroupAdd extends AbstractAddStepHandler implements DescriptionProvider {

    /**
     * Create an "add" operation using the existing model
     */
    public static ModelNode getAddOperation(final ModelNode address, ModelNode subModel) {

        final ModelNode operation = org.jboss.as.controller.operations.common.Util.getOperation(ADD, address, subModel);

        return operation;
    }

    public static final BroadcastGroupAdd INSTANCE = new BroadcastGroupAdd();

    private BroadcastGroupAdd() {
    }

    @Override
    protected void populateModel(ModelNode operation, ModelNode model) throws OperationFailedException {

        model.setEmptyObject();

        for (final AttributeDefinition attributeDefinition : CommonAttributes.BROADCAST_GROUP_ATTRIBUTES) {
            attributeDefinition.validateAndSet(operation, model);
        }
    }

    @Override
    protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model, ServiceVerificationHandler verificationHandler, List<ServiceController<?>> newControllers) throws OperationFailedException {

        ServiceRegistry registry = context.getServiceRegistry(false);
        ServiceController<?> hqService = registry.getService(MessagingServices.JBOSS_MESSAGING);
        if (hqService != null) {
            context.reloadRequired();
        } else {
            final PathAddress address = PathAddress.pathAddress(operation.require(OP_ADDR));
            final String name = address.getLastElement().getValue();

            final ServiceTarget target = context.getServiceTarget();
            if(model.hasDefined(CommonAttributes.SOCKET_BINDING.getName())) {
                final GroupBindingService bindingService = new GroupBindingService();
                target.addService(GroupBindingService.BASE.append(name), bindingService)
                        .addDependency(SocketBinding.JBOSS_BINDING_NAME.append(model.get(SOCKET_BINDING).asString()), SocketBinding.class, bindingService.getBindingRef())
                        .install();
            } else {

                final ModelNode localAddrNode = CommonAttributes.LOCAL_BIND_ADDRESS.validateResolvedOperation(model);
                final String localAddress = localAddrNode.isDefined() ? localAddrNode.asString() : null;
                final String groupAddress = CommonAttributes.GROUP_ADDRESS.validateResolvedOperation(model).asString();
                final int groupPort = CommonAttributes.GROUP_PORT.validateResolvedOperation(model).asInt();

                try {

                    final InetAddress inet = localAddress != null ? InetAddress.getByName(localAddress) : InetAddress.getLocalHost();
                    final NetworkInterface intf = NetworkInterface.getByInetAddress(inet);
                    final NetworkInterfaceBinding b = new NetworkInterfaceBinding(Collections.singleton(intf), inet);
                    final InetAddress group = InetAddress.getByName(groupAddress);

                    final SocketBinding socketBinding = new SocketBinding(name, -1, false, group, groupPort, b, null);

                    final GroupBindingService bindingService = new GroupBindingService();
                    target.addService(GroupBindingService.BASE.append(name), bindingService)
                            .addInjectionValue(bindingService.getBindingRef(), new ImmediateValue<SocketBinding>(socketBinding))
                            .install();

                } catch (Exception e) {
                    throw new OperationFailedException(new ModelNode().set(e.getMessage()));
                }
            }
        }
    }

    @Override
    public ModelNode getModelDescription(Locale locale) {
        return MessagingDescriptions.getBroadcastGroupAdd(locale);
    }

    static void addBroadcastGroupConfigs(final Configuration configuration, final ModelNode model)  throws OperationFailedException {
        if (model.hasDefined(CommonAttributes.BROADCAST_GROUP)) {
            final List<BroadcastGroupConfiguration> configs = configuration.getBroadcastGroupConfigurations();
            for (Property prop : model.get(CommonAttributes.BROADCAST_GROUP).asPropertyList()) {
                configs.add(createBroadcastGroupConfiguration(prop.getName(), prop.getValue()));

            }
        }
    }

    static BroadcastGroupConfiguration createBroadcastGroupConfiguration(final String name, final ModelNode model) throws OperationFailedException {

        final ModelNode localAddrNode = CommonAttributes.LOCAL_BIND_ADDRESS.validateResolvedOperation(model);
        final String localAddress = localAddrNode.isDefined() ? localAddrNode.asString() : null;
        final int localPort = CommonAttributes.LOCAL_BIND_PORT.validateResolvedOperation(model).asInt();
        final String groupAddress = CommonAttributes.GROUP_ADDRESS.validateResolvedOperation(model).asString();
        final int groupPort = CommonAttributes.GROUP_PORT.validateResolvedOperation(model).asInt(0);
        final long broadcastPeriod = CommonAttributes.BROADCAST_PERIOD.validateResolvedOperation(model).asLong();
        final List<String> connectorRefs = new ArrayList<String>();
        if (model.hasDefined(CommonAttributes.CONNECTORS)) {
            for (ModelNode ref : model.get(CommonAttributes.CONNECTORS).asList()) {
                connectorRefs.add(ref.asString());
            }
        }

        return new BroadcastGroupConfiguration(name, localAddress, localPort, groupAddress, groupPort, broadcastPeriod, connectorRefs);
    }

    static BroadcastGroupConfiguration createBroadcastGroupConfiguration(final String name, BroadcastGroupConfiguration config, SocketBinding socketBinding) {

        final String localAddress = socketBinding.getAddress().toString();
        final String groupAddress = socketBinding.getMulticastAddress().toString();
        final int localPort = socketBinding.getPort();
        final int groupPort = socketBinding.getMulticastPort();
        final long broadcastPeriod = config.getBroadcastPeriod();
        final List<String> connectorRefs = config.getConnectorInfos();

        return new BroadcastGroupConfiguration(name, localAddress, localPort, groupAddress, groupPort, broadcastPeriod, connectorRefs);
    }

}
