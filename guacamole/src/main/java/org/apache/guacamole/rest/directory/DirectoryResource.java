/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.guacamole.rest.directory;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import org.apache.guacamole.GuacamoleClientException;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleResourceNotFoundException;
import org.apache.guacamole.GuacamoleUnsupportedException;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.AuthenticationProvider;
import org.apache.guacamole.net.auth.Directory;
import org.apache.guacamole.net.auth.Identifiable;
import org.apache.guacamole.net.auth.Permissions;
import org.apache.guacamole.net.auth.UserContext;
import org.apache.guacamole.net.auth.permission.ObjectPermission;
import org.apache.guacamole.net.auth.permission.ObjectPermissionSet;
import org.apache.guacamole.net.auth.permission.SystemPermission;
import org.apache.guacamole.net.auth.permission.SystemPermissionSet;
import org.apache.guacamole.net.event.DirectoryEvent;
import org.apache.guacamole.net.event.DirectoryFailureEvent;
import org.apache.guacamole.net.event.DirectorySuccessEvent;
import org.apache.guacamole.rest.APIPatch;
import org.apache.guacamole.rest.event.ListenerService;

/**
 * A REST resource which abstracts the operations available on all Guacamole
 * Directory implementations, such as the creation of new objects, or listing
 * of existing objects. A DirectoryResource functions as the parent of any
 * number of child DirectoryObjectResources, which are created with the factory
 * provided at the time of this object's construction.
 *
 * @param <InternalType>
 *     The type of object contained within the Directory that this
 *     DirectoryResource exposes. To avoid coupling the REST API too tightly to
 *     the extension API, these objects are not directly serialized or
 *     deserialized when handling REST requests.
 *
 * @param <ExternalType>
 *     The type of object used in interchange (ie: serialized/deserialized as
 *     JSON) between REST clients and this DirectoryResource when representing
 *     the InternalType.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public abstract class DirectoryResource<InternalType extends Identifiable, ExternalType> {

    /**
     * The user that is accessing this resource.
     */
    private final AuthenticatedUser authenticatedUser;
    
    /**
     * The UserContext associated with the Directory being exposed by this
     * DirectoryResource.
     */
    private final UserContext userContext;

    /**
     * The type of object contained within the Directory exposed by this
     * DirectoryResource.
     */
    private final Class<InternalType> internalType;

    /**
     * The Directory being exposed by this DirectoryResource.
     */
    private final Directory<InternalType> directory;

    /**
     * A DirectoryObjectTranslator implementation which handles the type of
     * objects contained within the Directory exposed by this DirectoryResource.
     */
    private final DirectoryObjectTranslator<InternalType, ExternalType> translator;

    /**
     * A factory which can be used to create instances of resources representing
     * individual objects contained within the Directory exposed by this
     * DirectoryResource.
     */
    private final DirectoryObjectResourceFactory<InternalType, ExternalType> resourceFactory;

    /**
     * Service for dispatching events to registered listeners.
     */
    @Inject
    private ListenerService listenerService;

    /**
     * Creates a new DirectoryResource which exposes the operations available
     * for the given Directory.
     *
     * @param authenticatedUser
     *     The user that is accessing this resource.
     *
     * @param userContext
     *     The UserContext associated with the given Directory.
     *
     * @param internalType
     *     The type of object contained within the given Directory.
     *
     * @param directory
     *     The Directory being exposed by this DirectoryResource.
     *
     * @param translator
     *     A DirectoryObjectTranslator implementation which handles the type of
     *     objects contained within the given Directory.
     *
     * @param resourceFactory
     *     A factory which can be used to create instances of resources
     *     representing individual objects contained within the given Directory.
     */
    public DirectoryResource(AuthenticatedUser authenticatedUser,
            UserContext userContext, Class<InternalType> internalType,
            Directory<InternalType> directory,
            DirectoryObjectTranslator<InternalType, ExternalType> translator,
            DirectoryObjectResourceFactory<InternalType, ExternalType> resourceFactory) {
        this.authenticatedUser = authenticatedUser;
        this.userContext = userContext;
        this.directory = directory;
        this.internalType = internalType;
        this.translator = translator;
        this.resourceFactory = resourceFactory;
    }

    /**
     * Returns the ObjectPermissionSet defined within the given Permissions
     * that represents the permissions affecting objects available within this
     * DirectoryResource.
     *
     * @param permissions
     *     The Permissions object from which the ObjectPermissionSet should be
     *     retrieved.
     *
     * @return
     *     The ObjectPermissionSet defined within the given Permissions object
     *     that represents the permissions affecting objects available within
     *     this DirectoryResource.
     *
     * @throws GuacamoleException
     *     If an error prevents retrieval of permissions.
     */
    protected abstract ObjectPermissionSet getObjectPermissions(
            Permissions permissions) throws GuacamoleException;

    /**
     * Notifies all registered listeners that the given operation has succeeded
     * against the object having the given identifier within the Directory
     * represented by this resource.
     * 
     * @param operation
     *     The operation that was performed.
     *
     * @param identifier
     *     The identifier of the object affected by the operation.
     *
     * @param object
     *     The specific object affected by the operation, if available. If not
     *     available, this may be null.
     *
     * @throws GuacamoleException
     *     If a listener throws a GuacamoleException from its event handler.
     */
    protected void fireDirectorySuccessEvent(DirectoryEvent.Operation operation,
                String identifier, InternalType object)
            throws GuacamoleException {
        listenerService.handleEvent(new DirectorySuccessEvent<InternalType>() {

            @Override
            public Directory.Type getDirectoryType() {
                return Directory.Type.of(internalType);
            }

            @Override
            public DirectoryEvent.Operation getOperation() {
                return operation;
            }

            @Override
            public String getObjectIdentifier() {
                return identifier;
            }

            @Override
            public InternalType getObject() {
                return object;
            }

            @Override
            public AuthenticatedUser getAuthenticatedUser() {
                return authenticatedUser;
            }

            @Override
            public AuthenticationProvider getAuthenticationProvider() {
                return userContext.getAuthenticationProvider();
            }

        });
    }

    /**
     * Notifies all registered listeners that the given operation has failed
     * against the object having the given identifier within the Directory
     * represented by this resource.
     *
     * @param operation
     *     The operation that failed.
     *
     * @param identifier
     *     The identifier of the object that would have been affected by the
     *     operation had it succeeded.
     *
     * @param object
     *     The specific object would have been affected by the operation, if
     *     available, had it succeeded, including any changes that were
     *     intended to be applied to the object. If not available, this may be
     *     null.
     *
     * @param failure
     *     The failure that occurred.
     *
     * @throws GuacamoleException
     *     If a listener throws a GuacamoleException from its event handler.
     */
    protected void fireDirectoryFailureEvent(DirectoryEvent.Operation operation,
                String identifier, InternalType object, Throwable failure)
            throws GuacamoleException {
        listenerService.handleEvent(new DirectoryFailureEvent<InternalType>() {

            @Override
            public Directory.Type getDirectoryType() {
                return Directory.Type.of(internalType);
            }

            @Override
            public DirectoryEvent.Operation getOperation() {
                return operation;
            }

            @Override
            public String getObjectIdentifier() {
                return identifier;
            }

            @Override
            public InternalType getObject() {
                return object;
            }

            @Override
            public AuthenticatedUser getAuthenticatedUser() {
                return authenticatedUser;
            }

            @Override
            public AuthenticationProvider getAuthenticationProvider() {
                return userContext.getAuthenticationProvider();
            }

            @Override
            public Throwable getFailure() {
                return failure;
            }

        });
    }

    /**
     * Returns the user accessing this resource.
     *
     * @return
     *     The user accessing this resource.
     */
    protected AuthenticatedUser getAuthenticatedUser() {
        return authenticatedUser;
    }

    /**
     * Returns the UserContext providing the Directory exposed by this
     * resource.
     *
     * @return 
     *     The UserContext providing the Directory exposed by this resource.
     */
    protected UserContext getUserContext() {
        return userContext;
    }

    /**
     * Returns the Directory exposed by this resource.
     *
     * @return 
     *     The Directory exposed by this resource.
     */
    protected Directory<InternalType> getDirectory() {
        return directory;
    }

    /**
     * Returns a factory that can be used to create instances of resources
     * representing individual objects contained within the Directory exposed
     * by this resource.
     *
     * @return 
     *     A factory that can be used to create instances of resources
     *     representing individual objects contained within the Directory
     *     exposed by this resource.
     */
    public DirectoryObjectResourceFactory<InternalType, ExternalType> getResourceFactory() {
        return resourceFactory;
    }

    /**
     * Returns a map of all objects available within this DirectoryResource,
     * filtering the returned map by the given permission, if specified.
     *
     * @param permissions
     *     The set of permissions to filter with. A user must have one or more
     *     of these permissions for the affected objects to appear in the
     *     result. If null, no filtering will be performed.
     *
     * @return
     *     A map of all visible objects. If a permission was specified, this
     *     map will contain only those objects for which the current user has
     *     that permission.
     *
     * @throws GuacamoleException
     *     If an error is encountered while retrieving the objects.
     */
    @GET
    public Map<String, ExternalType> getObjects(
            @QueryParam("permission") List<ObjectPermission.Type> permissions)
            throws GuacamoleException {

        // An admin user has access to all objects
        Permissions effective = userContext.self().getEffectivePermissions();
        SystemPermissionSet systemPermissions = effective.getSystemPermissions();
        boolean isAdmin = systemPermissions.hasPermission(SystemPermission.Type.ADMINISTER);

        // Filter objects, if requested
        Collection<String> identifiers = directory.getIdentifiers();
        if (!isAdmin && permissions != null && !permissions.isEmpty()) {
            ObjectPermissionSet objectPermissions = getObjectPermissions(effective);
            identifiers = objectPermissions.getAccessibleObjects(permissions, identifiers);
        }

        // Translate each retrieved object into the corresponding external object
        Map<String, ExternalType> apiObjects = new HashMap<String, ExternalType>();
        for (InternalType object : directory.getAll(identifiers))
            apiObjects.put(object.getIdentifier(), translator.toExternalObject(object));

        return apiObjects;

    }

    /**
     * Applies the given object patches, updating the underlying directory
     * accordingly. This operation currently only supports deletion of objects
     * through the "remove" patch operation. The path of each patch operation is
     * of the form "/ID" where ID is the identifier of the object being
     * modified.
     *
     * @param patches
     *     The patches to apply for this request.
     *
     * @throws GuacamoleException
     *     If an error occurs while deleting the objects.
     */
    @PATCH
    public void patchObjects(List<APIPatch<String>> patches)
            throws GuacamoleException {

        // Apply each operation specified within the patch
        for (APIPatch<String> patch : patches) {

            // Only remove is supported
            if (patch.getOp() != APIPatch.Operation.remove)
                throw new GuacamoleUnsupportedException("Only the \"remove\" "
                        + "operation is supported.");

            // Retrieve and validate path
            String path = patch.getPath();
            if (!path.startsWith("/"))
                throw new GuacamoleClientException("Patch paths must start with \"/\".");

            // Remove specified object
            String identifier = path.substring(1);
            try {
                directory.remove(identifier);
                fireDirectorySuccessEvent(DirectoryEvent.Operation.REMOVE, identifier, null);
            }
            catch (GuacamoleException | RuntimeException | Error e) {
                fireDirectoryFailureEvent(DirectoryEvent.Operation.REMOVE, identifier, null, e);
                throw e;
            }

        }

    }

    /**
     * Creates a new object within the underlying Directory, returning the
     * object that was created. The identifier of the created object will be
     * populated, if applicable.
     *
     * @param object
     *     The object to create.
     *
     * @return
     *     The object that was created.
     *
     * @throws GuacamoleException
     *     If an error occurs while adding the object to the underlying
     *     directory.
     */
    @POST
    public ExternalType createObject(ExternalType object)
            throws GuacamoleException {

        // Validate that data was provided
        if (object == null)
            throw new GuacamoleClientException("Data must be submitted when creating objects.");

        // Filter/sanitize object contents
        translator.filterExternalObject(userContext, object);
        InternalType internal = translator.toInternalObject(object);

        // Create the new object within the directory
        try {
            directory.add(internal);
            fireDirectorySuccessEvent(DirectoryEvent.Operation.ADD, internal.getIdentifier(), internal);
            return object;
        }
        catch (GuacamoleException | RuntimeException | Error e) {
            fireDirectoryFailureEvent(DirectoryEvent.Operation.ADD, internal.getIdentifier(), internal, e);
            throw e;
        }

    }

    /**
     * Retrieves an individual object, returning a DirectoryObjectResource
     * implementation which exposes operations available on that object.
     *
     * @param identifier
     *     The identifier of the object to retrieve.
     *
     * @return
     *     A DirectoryObjectResource exposing operations available on the
     *     object having the given identifier.
     *
     * @throws GuacamoleException
     *     If an error occurs while retrieving the object.
     */
    @Path("{identifier}")
    public DirectoryObjectResource<InternalType, ExternalType>
        getObjectResource(@PathParam("identifier") String identifier)
            throws GuacamoleException {

        // Retrieve the object having the given identifier
        InternalType object;
        try {
            object = directory.get(identifier);
            if (object == null)
                throw new GuacamoleResourceNotFoundException("Not found: \"" + identifier + "\"");
        }
        catch (GuacamoleException | RuntimeException | Error e) {
            fireDirectoryFailureEvent(DirectoryEvent.Operation.GET, identifier, null, e);
            throw e;
        }

        // Return a resource which provides access to the retrieved object
        DirectoryObjectResource<InternalType, ExternalType> resource = resourceFactory.create(authenticatedUser, userContext, directory, object);
        fireDirectorySuccessEvent(DirectoryEvent.Operation.GET, identifier, object);
        return resource;

    }

}
