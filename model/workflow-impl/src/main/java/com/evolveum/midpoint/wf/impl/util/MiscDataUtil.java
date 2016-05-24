/*
 * Copyright (c) 2010-2013 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evolveum.midpoint.wf.impl.util;

import com.evolveum.midpoint.common.SystemConfigurationHolder;
import com.evolveum.midpoint.model.api.ModelAuthorizationAction;
import com.evolveum.midpoint.model.api.ModelInteractionService;
import com.evolveum.midpoint.model.api.context.ModelContext;
import com.evolveum.midpoint.model.api.context.ModelElementContext;
import com.evolveum.midpoint.model.impl.lens.LensFocusContext;
import com.evolveum.midpoint.model.impl.lens.LensProjectionContext;
import com.evolveum.midpoint.prism.*;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.DeltaConvertor;
import com.evolveum.midpoint.schema.ObjectTreeDeltas;
import com.evolveum.midpoint.schema.ResourceShadowDiscriminator;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.util.MiscSchemaUtil;
import com.evolveum.midpoint.schema.util.OidUtil;
import com.evolveum.midpoint.security.api.AuthorizationConstants;
import com.evolveum.midpoint.security.api.MidPointPrincipal;
import com.evolveum.midpoint.security.api.SecurityEnforcer;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.task.api.TaskManager;
import com.evolveum.midpoint.util.exception.*;
import com.evolveum.midpoint.util.logging.LoggingUtils;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.wf.impl.WfConfiguration;
import com.evolveum.midpoint.wf.impl.activiti.ActivitiEngine;
import com.evolveum.midpoint.wf.impl.processes.common.CommonProcessVariableNames;
import com.evolveum.midpoint.wf.impl.processes.common.LightweightObjectRef;
import com.evolveum.midpoint.wf.impl.processors.BaseModelInvocationProcessingHelper;
import com.evolveum.midpoint.wf.impl.processors.primary.WfPrepareChildOperationTaskHandler;
import com.evolveum.midpoint.wf.impl.processors.primary.WfPrepareRootOperationTaskHandler;
import com.evolveum.midpoint.wf.util.ChangesByState;
import com.evolveum.midpoint.xml.ns._public.common.common_3.*;
import com.evolveum.prism.xml.ns._public.types_3.ObjectDeltaType;
import org.activiti.engine.ActivitiException;
import org.activiti.engine.TaskService;
import org.activiti.engine.form.FormProperty;
import org.activiti.engine.task.IdentityLink;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.xml.bind.JAXBException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.evolveum.midpoint.prism.delta.ChangeType.ADD;
import static com.evolveum.midpoint.schema.ObjectTreeDeltas.fromObjectTreeDeltasType;

/**
 * @author mederly
 */

@Component
public class MiscDataUtil {

    private static final transient Trace LOGGER = TraceManager.getTrace(MiscDataUtil.class);

    public static final String ROLE_PREFIX = "role";
    public static final String ORG_PREFIX = "org";

    @Autowired
    @Qualifier("cacheRepositoryService")
    private RepositoryService repositoryService;

    @Autowired
    private PrismContext prismContext;

    @Autowired
    private TaskManager taskManager;
    
    @Autowired
    private SecurityEnforcer securityEnforcer;

    @Autowired
    private WfConfiguration wfConfiguration;

    @Autowired
    private ActivitiEngine activitiEngine;

	@Autowired
	private BaseModelInvocationProcessingHelper baseModelInvocationProcessingHelper;

    public static ObjectReferenceType toObjectReferenceType(LightweightObjectRef ref) {
		if (ref != null) {
			return ref.toObjectReferenceType();
		} else {
			return null;
		}
	}

    //region ========================================================================== Miscellaneous
    public PrismObject<UserType> getUserByOid(String oid, OperationResult result) {
        if (oid == null) {
            return null;
        }
        try {
            return repositoryService.getObject(UserType.class, oid, null, result);
        } catch (ObjectNotFoundException e) {
            // there should be a note in result by now
            LoggingUtils.logException(LOGGER, "Couldn't get user {} details because it couldn't be found", e, oid);
            return null;
        } catch (SchemaException e) {
            // there should be a note in result by now
            LoggingUtils.logException(LOGGER, "Couldn't get user {} details due to schema exception", e, oid);
            return null;
        }
    }


    // returns oid when user cannot be retrieved
    public String getUserNameByOid(String oid, OperationResult result) {
        try {
            PrismObject<UserType> user = repositoryService.getObject(UserType.class, oid, null, result);
            return user.asObjectable().getName().getOrig();
        } catch (ObjectNotFoundException e) {
            // there should be a note in result by now
            LoggingUtils.logException(LOGGER, "Couldn't get user {} details because it couldn't be found", e, oid);
            return oid;
        } catch (SchemaException e) {
            // there should be a note in result by now
            LoggingUtils.logException(LOGGER, "Couldn't get user {} details due to schema exception", e, oid);
            return oid;
        }
    }

    public ObjectDelta getFocusPrimaryDelta(WfContextType workflowContext, boolean mayBeNull) throws JAXBException, SchemaException {
        ObjectDeltaType objectDeltaType = getFocusPrimaryObjectDeltaType(workflowContext, mayBeNull);
        return objectDeltaType != null ? DeltaConvertor.createObjectDelta(objectDeltaType, prismContext) : null;
    }

    // mayBeNull=false means that the corresponding variable must be present (not that focus must be non-null)
    // TODO: review/correct this!
    public ObjectDeltaType getFocusPrimaryObjectDeltaType(WfContextType workflowContext, boolean mayBeNull) throws JAXBException, SchemaException {
        ObjectTreeDeltasType deltas = getObjectTreeDeltaType(workflowContext, mayBeNull);
        return deltas != null ? deltas.getFocusPrimaryDelta() : null;
    }

    public ObjectTreeDeltasType getObjectTreeDeltaType(WfContextType workflowContext, boolean mayBeNull) throws SchemaException {
		WfProcessorSpecificStateType state = workflowContext.getProcessorSpecificState();
		if (mayBeNull && state == null) {
			return null;
		}
		if (!(state instanceof WfPrimaryChangeProcessorStateType)) {
			throw new IllegalStateException("Expected WfPrimaryChangeProcessorStateType but got " + state);
		}
		return ((WfPrimaryChangeProcessorStateType) state).getDeltasToProcess();
    }

    public static String serializeObjectToXml(PrismObject<? extends ObjectType> object) {
        return serializeObjectToXml(object, object.getPrismContext());
    }

    public static String serializeObjectToXml(PrismObject<? extends ObjectType> object, PrismContext prismContext) {
        try {
            return prismContext.serializeObjectToString(object, PrismContext.LANG_XML);
        } catch (SchemaException e) {
            throw new SystemException("Couldn't serialize a PrismObject " + object + " into XML", e);
        }
    }

    public static String serializeContainerableToXml(Containerable containerable, PrismContext prismContext) {
        try {
            PrismContainerValue value = containerable.asPrismContainerValue();
            return prismContext.serializeContainerValueToString(value, value.getContainer().getElementName(), PrismContext.LANG_XML);
        } catch (SchemaException e) {
            throw new SystemException("Couldn't serialize a Containerable " + containerable + " into XML", e);
        }
    }

    public static ObjectType deserializeObjectFromXml(String xml, PrismContext prismContext) {
        try {
            return (ObjectType) prismContext.parseObject(xml, PrismContext.LANG_XML).asObjectable();
        } catch (SchemaException e) {
            throw new SystemException("Couldn't deserialize a PrismObject from XML", e);
        }
    }

    public static PrismContainer deserializeContainerFromXml(String xml, PrismContext prismContext) {
        try {
            return prismContext.parseContainer(xml, (Class) null, PrismContext.LANG_XML);
        } catch (SchemaException e) {
            throw new SystemException("Couldn't deserialize a Containerable from XML", e);
        }
    }

    public void resolveAssignmentTargetReferences(PrismObject<? extends UserType> object, OperationResult result) {
        for (AssignmentType assignmentType : object.asObjectable().getAssignment()) {
            if (assignmentType.getTarget() == null && assignmentType.getTargetRef() != null) {
                PrismObject<? extends ObjectType> target = null;
                try {
                    target = repositoryService.getObject(ObjectType.class, assignmentType.getTargetRef().getOid(), null, result);
                    assignmentType.setTarget(target.asObjectable());
                } catch (ObjectNotFoundException e) {
                    LoggingUtils.logException(LOGGER, "Couldn't resolve assignment " + assignmentType, e);
                } catch (SchemaException e) {
                    LoggingUtils.logException(LOGGER, "Couldn't resolve assignment " + assignmentType, e);
                }
            }
        }
    }

    /**
     * Retrieves focus object name from the model context.
     */
    public static String getFocusObjectName(ModelContext<? extends ObjectType> modelContext) {
        ObjectType object = getFocusObjectNewOrOld(modelContext);
        return object.getName() != null ? object.getName().getOrig() : null;
    }

    public static String getFocusObjectOid(ModelContext<?> modelContext) {
        ModelElementContext<?> fc = modelContext.getFocusContext();
        if (fc.getObjectNew() != null && fc.getObjectNew().getOid() != null) {
            return fc.getObjectNew().getOid();
        } else if (fc.getObjectOld() != null && fc.getObjectOld().getOid() != null) {
            return fc.getObjectOld().getOid();
        } else {
            return null;
        }
    }

    public static ObjectType getFocusObjectNewOrOld(ModelContext<? extends ObjectType> modelContext) {
        ModelElementContext<? extends ObjectType> fc = modelContext.getFocusContext();
        PrismObject<? extends ObjectType> prism = fc.getObjectNew() != null ? fc.getObjectNew() : fc.getObjectOld();
        if (prism == null) {
            throw new IllegalStateException("No object (new or old) in model context");
        }
        return prism.asObjectable();
    }

    public Task getShadowTask(Map<String, Object> variables, OperationResult result) throws SchemaException, ObjectNotFoundException {
        String oid = (String) variables.get(CommonProcessVariableNames.VARIABLE_MIDPOINT_TASK_OID);
        if (oid != null) {
            return taskManager.getTask(oid, result);
        } else {
            return null;
        }
    }

    public boolean isAuthorizedToSubmit(WorkItemType workItem) {
        return isAuthorizedToSubmit(workItem.getWorkItemId(),
                workItem.getAssigneeRef() != null ? workItem.getAssigneeRef().getOid() : null);
    }

    public boolean isAuthorizedToSubmit(String taskId, String assigneeOid) {
        MidPointPrincipal principal;
		try {
			principal = securityEnforcer.getPrincipal();
		} catch (SecurityViolationException e) {
			return false;
		}
        String currentUserOid = principal.getOid();
        if (currentUserOid == null) {
            return false;
        }
        LOGGER.trace("isAuthorizedToSubmit: principal = {}, assignee = {}", principal, assigneeOid);
        // 1) is this the task assignee?
        if (currentUserOid.equals(assigneeOid)) {       // assigneeOid may be null here
            return true;
        }
        // 2) is the current user allowed to approve any item?
        try {
			WfConfigurationType wfConfig = SystemConfigurationHolder.getWorkflowConfiguration();
			boolean allowedOthersItemsApproval = wfConfig != null && wfConfig.isAllowCompleteOthersItems() != null ? wfConfig.isAllowCompleteOthersItems() : false;
			if (allowedOthersItemsApproval
					&& securityEnforcer.isAuthorized(ModelAuthorizationAction.COMPLETE_ALL_WORK_ITEMS.getUrl(), null, null, null, null, null)) {
                return true;
            }
		} catch (SchemaException e) {
			throw new SystemException(e.getMessage(), e);
		}
        // 3) is the current user in one of candidate users or groups?
        return isAmongCandidates(principal, taskId);
    }

    // principal != null, principal.getOid() != null, principal.getUser() != null
    private boolean isAmongCandidates(MidPointPrincipal principal, String taskId) {
        String currentUserOid = principal.getOid();
        List<IdentityLink> identityLinks;
        try {
            TaskService taskService = activitiEngine.getTaskService();
            identityLinks = taskService.getIdentityLinksForTask(taskId);
        } catch (ActivitiException e) {
            throw new SystemException("Couldn't determine user authorization, because the task candidate users and groups couldn't be retrieved: " + e.getMessage(), e);
        }
        for (IdentityLink identityLink : identityLinks) {
            if (identityLink.getUserId() != null && identityLink.getUserId().equals(currentUserOid)) {
                return true;
            }
            if (identityLink.getGroupId() != null) {
                if (isMemberOfActivitiGroup(principal.getUser(), identityLink.getGroupId())) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isAuthorizedToClaim(WorkItemType workItem) {
        return isAuthorizedToClaim(workItem.getWorkItemId());
    }

    public boolean isAuthorizedToClaim(String taskId) {
        MidPointPrincipal principal;
        try {
            principal = securityEnforcer.getPrincipal();
        } catch (SecurityViolationException e) {
            return false;
        }
        String currentUserOid = principal.getOid();
        if (currentUserOid == null) {
            return false;
        }
        return isAmongCandidates(principal, taskId);
    }


    // todo move to something activiti-related

    public static Map<String,FormProperty> formPropertiesAsMap(List<FormProperty> properties) {
        Map<String,FormProperty> retval = new HashMap<String,FormProperty>();
        for (FormProperty property : properties) {
            retval.put(property.getId(), property);
        }
        return retval;
    }

    // TODO: currently we check only the direct assignments, we need to implement more complex mechanism
    public boolean isMemberOfActivitiGroup(UserType userType, String activitiGroupId) {
        ObjectReferenceType abstractRoleRef = groupIdToObjectReference(activitiGroupId);
        for (AssignmentType assignmentType : userType.getAssignment()) {
            if (assignmentType.getTargetRef() != null &&
                    (RoleType.COMPLEX_TYPE.equals(assignmentType.getTargetRef().getType()) ||
                     OrgType.COMPLEX_TYPE.equals(assignmentType.getTargetRef().getType())) &&
                    abstractRoleRef.getOid().equals(assignmentType.getTargetRef().getOid())) {
                return true;
            }
        }
        return false;
    }

	public PrismObject resolveObjectReference(ObjectReferenceType ref, OperationResult result) {
		return resolveObjectReference(ref, false, result);
	}

	public PrismObject resolveAndStoreObjectReference(ObjectReferenceType ref, OperationResult result) {
		return resolveObjectReference(ref, true, result);
	}

    private PrismObject resolveObjectReference(ObjectReferenceType ref, boolean storeBack, OperationResult result) {
        if (ref == null) {
            return null;
        }
		if (ref.asReferenceValue().getObject() != null) {
			return ref.asReferenceValue().getObject();
		}
        try {
            PrismObject object = repositoryService.getObject((Class) prismContext.getSchemaRegistry().getCompileTimeClass(ref.getType()), ref.getOid(), null, result);
			if (storeBack) {
				ref.asReferenceValue().setObject(object);
			}
            return object;
        } catch (ObjectNotFoundException e) {
            // there should be a note in result by now
            LoggingUtils.logException(LOGGER, "Couldn't get reference {} details because it couldn't be found", e, ref);
            return null;
        } catch (SchemaException e) {
            // there should be a note in result by now
            LoggingUtils.logUnexpectedException(LOGGER, "Couldn't get reference {} details due to schema exception", e, ref);
            return null;
        }
    }

    public ObjectReferenceType resolveObjectReferenceName(ObjectReferenceType ref, OperationResult result) {
        if (ref == null || ref.getTargetName() != null) {
            return ref;
        }
        PrismObject<?> object;
        if (ref.asReferenceValue().getObject() != null) {
            object = ref.asReferenceValue().getObject();
        } else {
            object = resolveObjectReference(ref, result);
            if (object == null) {
                return ref;
            }
        }
        ref = ref.clone();
        ref.setTargetName(PolyString.toPolyStringType(object.getName()));
        return ref;
    }

    public ObjectReferenceType groupIdToObjectReference(String groupId) {
        String parts[] = groupId.split(":");
        if (parts.length != 2) {
            throw new IllegalStateException("Invalid format of group id: " + groupId);
        }
        if (ROLE_PREFIX.equals(parts[0])) {
            return MiscSchemaUtil.createObjectReference(parts[1], RoleType.COMPLEX_TYPE);
        } else if (ORG_PREFIX.equals(parts[0])) {
            return MiscSchemaUtil.createObjectReference(parts[1], OrgType.COMPLEX_TYPE);
        } else {
            throw new IllegalStateException("Unknown kind of group id: " + parts[0] + " in: " + groupId);
        }
    }

    private String objectReferenceToGroupName(ObjectReferenceType ref) {
        if (RoleType.COMPLEX_TYPE.equals(ref.getType())) {
            return ROLE_PREFIX + ":" + ref.getOid();
        } else if (OrgType.COMPLEX_TYPE.equals(ref.getType())) {
            return ORG_PREFIX + ":" + ref.getOid();
        } else {
            return null;
        }
    }

	public void generateFocusOidIfNeeded(ModelContext<?> modelContext, ObjectDelta<? extends ObjectType> change) {
		if (modelContext.getFocusContext().getOid() != null) {
			return;
		}

		String newOid = OidUtil.generateOid();
		LOGGER.trace("This is ADD operation with no focus OID provided. Generated new OID to be used: {}", newOid);
		if (change.getChangeType() != ADD) {
			throw new IllegalStateException("Change type is not ADD for no-oid focus situation: " + change);
		} else if (change.getObjectToAdd() == null) {
			throw new IllegalStateException("Object to add is null for change: " + change);
		} else if (change.getObjectToAdd().getOid() != null) {
			throw new IllegalStateException("Object to add has already an OID present: " + change);
		}
		change.getObjectToAdd().setOid(newOid);
		((LensFocusContext<?>) modelContext.getFocusContext()).setOid(newOid);
	}

	public void generateProjectionOidIfNeeded(ModelContext<?> modelContext, ShadowType shadow, ResourceShadowDiscriminator rsd) {
		if (shadow.getOid() != null) {
			return;
		}
		String newOid = OidUtil.generateOid();
		LOGGER.trace("This is ADD operation with no shadow OID for {} provided. Generated new OID to be used: {}", rsd, newOid);
		shadow.setOid(newOid);
		LensProjectionContext projCtx = ((LensProjectionContext) modelContext.findProjectionContext(rsd));
		if (projCtx == null) {
			throw new IllegalStateException("No projection context for " + rsd + " could be found");
		} else if (projCtx.getOid() != null) {
			throw new IllegalStateException("No projection context for " + rsd + " has already an OID: " + projCtx.getOid());
		}
		projCtx.setOid(newOid);
	}

	// TODO move somewhere else?
	public ChangesByState getChangesByStateForChild(TaskType childTask, TaskType rootTask, ModelInteractionService modelInteractionService, PrismContext prismContext, OperationResult result)
			throws SchemaException, ObjectNotFoundException {
		ChangesByState rv = new ChangesByState(prismContext);

		final WfContextType wfc = childTask.getWorkflowContext();
		if (wfc != null && wfc.getProcessInstanceId() != null) {
			if (wfc.isApproved() == null) {
				if (wfc.getEndTimestamp() == null) {
					recordChangesWaitingToBeApproved(rv, wfc, prismContext);
				} else {
					recordChangesCanceled(rv, wfc, prismContext);
				}
			} else if (wfc.isApproved()) {
				if (rootTask.getModelOperationContext() != null) {
					// this is "execute after all approvals"
					if (rootTask.getModelOperationContext().getState() == ModelStateType.FINAL) {
						recordResultingChanges(rv.getApplied(), wfc, prismContext);
					} else if (!containsHandler(rootTask, WfPrepareRootOperationTaskHandler.HANDLER_URI)) {
						recordResultingChanges(rv.getBeingApplied(), wfc, prismContext);
					} else {
						recordResultingChanges(rv.getWaitingToBeApplied(), wfc, prismContext);
					}
				} else {
					// "execute immediately"
					if (childTask.getModelOperationContext().getState() == ModelStateType.FINAL) {
						recordResultingChanges(rv.getApplied(), wfc, prismContext);
					} else if (!containsHandler(childTask, WfPrepareChildOperationTaskHandler.HANDLER_URI)) {
						recordResultingChanges(rv.getBeingApplied(), wfc, prismContext);
					} else {
						recordResultingChanges(rv.getWaitingToBeApplied(), wfc, prismContext);
					}
				}
			} else {
				recordChangesRejected(rv, wfc, prismContext);
			}
		}
		return rv;
	}

	// TODO move somewhere else?
	public ChangesByState getChangesByStateForRoot(TaskType rootTask, ModelInteractionService modelInteractionService, PrismContext prismContext, OperationResult result)
			throws SchemaException, ObjectNotFoundException {
		ChangesByState rv = new ChangesByState(prismContext);
		recordChanges(rv, rootTask.getModelOperationContext(), modelInteractionService, result);
		for (TaskType subtask : rootTask.getSubtask()) {
			recordChanges(rv, subtask.getModelOperationContext(), modelInteractionService, result);
			final WfContextType wfc = subtask.getWorkflowContext();
			if (wfc != null && wfc.getProcessInstanceId() != null) {
				if (wfc.isApproved() == null) {
					if (wfc.getEndTimestamp() == null) {
						recordChangesWaitingToBeApproved(rv, wfc, prismContext);
					} else {
						recordChangesCanceled(rv, wfc, prismContext);
					}
				} else if (wfc.isApproved()) {
					recordChangesApprovedIfNeeded(rv, subtask, rootTask, prismContext);
				} else {
					recordChangesRejected(rv, wfc, prismContext);
				}
			}
		}
		return rv;
	}

	private void recordChangesApprovedIfNeeded(ChangesByState rv, TaskType subtask, TaskType rootTask, PrismContext prismContext) throws SchemaException {
		if (!containsHandler(rootTask, WfPrepareRootOperationTaskHandler.HANDLER_URI) &&
				!containsHandler(subtask, WfPrepareChildOperationTaskHandler.HANDLER_URI)) {
			return;			// these changes were already incorporated into one of model contexts
		}
		if (subtask.getWorkflowContext().getProcessorSpecificState() instanceof WfPrimaryChangeProcessorStateType) {
			WfPrimaryChangeProcessorStateType ps = (WfPrimaryChangeProcessorStateType) subtask.getWorkflowContext().getProcessorSpecificState();
			rv.getWaitingToBeApplied().merge(fromObjectTreeDeltasType(ps.getResultingDeltas(), prismContext));
		}
	}

	private boolean containsHandler(TaskType taskType, String handlerUri) {
		if (handlerUri.equals(taskType.getHandlerUri())) {
			return true;
		}
		if (taskType.getOtherHandlersUriStack() == null) {
			return false;
		}
		for (UriStackEntry uriStackEntry : taskType.getOtherHandlersUriStack().getUriStackEntry()) {
			if (handlerUri.equals(uriStackEntry.getHandlerUri())) {
				return true;
			}
		}
		return false;
	}

	private <F extends FocusType> void recordChanges(ChangesByState rv, LensContextType modelOperationContext, ModelInteractionService modelInteractionService,
			OperationResult result) throws ObjectNotFoundException, SchemaException {
		if (modelOperationContext == null) {
			return;
		}
		ModelContext<F> modelContext = unwrapModelContext(modelOperationContext, modelInteractionService, result);
		ObjectTreeDeltas<F> deltas = baseModelInvocationProcessingHelper.extractTreeDeltasFromModelContext(modelContext);
		ObjectTreeDeltas<F> target;
		switch (modelContext.getState()) {
			case INITIAL:
			case PRIMARY: target = rv.getWaitingToBeApplied(); break;
			case SECONDARY: target = rv.getBeingApplied(); break;
			case EXECUTION:	// TODO reconsider this after EXECUTION and POSTEXECUTION states are really used
			case POSTEXECUTION:
			case FINAL: target = rv.getApplied(); break;
			default: throw new IllegalStateException("Illegal model state: " + modelContext.getState());
		}
		target.merge(deltas);
	}

	protected void recordChangesWaitingToBeApproved(ChangesByState rv, WfContextType wfc, PrismContext prismContext)
			throws SchemaException {
		if (wfc.getProcessorSpecificState() instanceof WfPrimaryChangeProcessorStateType) {
			WfPrimaryChangeProcessorStateType ps = (WfPrimaryChangeProcessorStateType) wfc.getProcessorSpecificState();
			rv.getWaitingToBeApproved().merge(fromObjectTreeDeltasType(ps.getDeltasToProcess(), prismContext));
		}
	}

	protected void recordChangesCanceled(ChangesByState rv, WfContextType wfc, PrismContext prismContext)
			throws SchemaException {
		if (wfc.getProcessorSpecificState() instanceof WfPrimaryChangeProcessorStateType) {
			WfPrimaryChangeProcessorStateType ps = (WfPrimaryChangeProcessorStateType) wfc.getProcessorSpecificState();
			rv.getCanceled().merge(fromObjectTreeDeltasType(ps.getDeltasToProcess(), prismContext));
		}
	}

	private void recordChangesRejected(ChangesByState rv, WfContextType wfc, PrismContext prismContext) throws SchemaException {
		if (wfc.getProcessorSpecificState() instanceof WfPrimaryChangeProcessorStateType) {
			WfPrimaryChangeProcessorStateType ps = (WfPrimaryChangeProcessorStateType) wfc.getProcessorSpecificState();
			if (ObjectTreeDeltas.isEmpty(ps.getResultingDeltas())) {
				rv.getRejected().merge(fromObjectTreeDeltasType(ps.getDeltasToProcess(), prismContext));
			} else {
				// it's actually hard to decide what to display as 'rejected' - because the delta was partly approved
				// however, this situation will not currently occur
			}
		}
	}

	private void recordResultingChanges(ObjectTreeDeltas<?> target, WfContextType wfc, PrismContext prismContext) throws SchemaException {
		if (wfc.getProcessorSpecificState() instanceof WfPrimaryChangeProcessorStateType) {
			WfPrimaryChangeProcessorStateType ps = (WfPrimaryChangeProcessorStateType) wfc.getProcessorSpecificState();
			target.merge(fromObjectTreeDeltasType(ps.getResultingDeltas(), prismContext));
		}
	}

	private ModelContext unwrapModelContext(LensContextType lensContextType, ModelInteractionService modelInteractionService, OperationResult result) throws
			ObjectNotFoundException {
		if (lensContextType != null) {
			try {
				return modelInteractionService.unwrapModelContext(lensContextType, result);
			} catch (SchemaException | CommunicationException | ConfigurationException e) {   // todo treat appropriately
				throw new SystemException("Couldn't access model operation context in task: " + e.getMessage(), e);
			}
		} else {
			return null;
		}
	}

}
