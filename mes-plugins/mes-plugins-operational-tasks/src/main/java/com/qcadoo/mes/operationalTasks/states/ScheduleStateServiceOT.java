package com.qcadoo.mes.operationalTasks.states;

import static com.qcadoo.model.api.search.SearchOrders.desc;
import static com.qcadoo.model.api.search.SearchProjections.alias;
import static com.qcadoo.model.api.search.SearchProjections.list;
import static com.qcadoo.model.api.search.SearchProjections.rowCount;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.qcadoo.mes.newstates.BasicStateService;
import com.qcadoo.mes.operationalTasks.constants.OperationalTaskFields;
import com.qcadoo.mes.operationalTasks.constants.OperationalTaskType;
import com.qcadoo.mes.operationalTasks.constants.OperationalTasksConstants;
import com.qcadoo.mes.operationalTasks.states.constants.OperationalTaskStateStringValues;
import com.qcadoo.mes.orders.constants.OrderFields;
import com.qcadoo.mes.orders.constants.OrdersConstants;
import com.qcadoo.mes.orders.constants.ScheduleFields;
import com.qcadoo.mes.orders.constants.SchedulePositionFields;
import com.qcadoo.mes.orders.states.ScheduleServiceMarker;
import com.qcadoo.mes.orders.states.ScheduleStateChangeDescriber;
import com.qcadoo.mes.orders.states.constants.OrderState;
import com.qcadoo.mes.orders.states.constants.ScheduleStateStringValues;
import com.qcadoo.mes.states.StateChangeEntityDescriber;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.search.JoinType;
import com.qcadoo.model.api.search.SearchOrders;
import com.qcadoo.model.api.search.SearchProjections;
import com.qcadoo.model.api.search.SearchRestrictions;
import com.qcadoo.plugin.api.RunIfEnabled;
import com.qcadoo.view.api.utils.NumberGeneratorService;

@Service
@RunIfEnabled(OperationalTasksConstants.PLUGIN_IDENTIFIER)
public class ScheduleStateServiceOT extends BasicStateService implements ScheduleServiceMarker {

    private static final String IS_SUBCONTRACTING = "isSubcontracting";

    @Autowired
    private ScheduleStateChangeDescriber scheduleStateChangeDescriber;

    @Autowired
    private DataDefinitionService dataDefinitionService;

    @Autowired
    private NumberGeneratorService numberGeneratorService;

    @Autowired
    private OperationalTaskOrderStateService operationalTaskOrderStateService;

    @Override
    public StateChangeEntityDescriber getChangeEntityDescriber() {
        return scheduleStateChangeDescriber;
    }

    @Override
    public Entity onValidate(Entity entity, String sourceState, String targetState, Entity stateChangeEntity,
            StateChangeEntityDescriber describer) {
        if (ScheduleStateStringValues.APPROVED.equals(targetState)) {
            checkExistingOperationalTasksState(entity);
        }

        return entity;
    }

    private void checkExistingOperationalTasksState(Entity entity) {
        List<Entity> scheduleOrders = entity.getManyToManyField(ScheduleFields.ORDERS);
        if (!scheduleOrders.isEmpty()) {
            List<Entity> orders = dataDefinitionService
                    .get(OperationalTasksConstants.PLUGIN_IDENTIFIER, OperationalTasksConstants.MODEL_OPERATIONAL_TASK).find()
                    .add(SearchRestrictions.ne(OperationalTaskFields.STATE, OperationalTaskStateStringValues.REJECTED))
                    .createAlias(OperationalTaskFields.ORDER, OperationalTaskFields.ORDER, JoinType.INNER)
                    .add(SearchRestrictions.in(OperationalTaskFields.ORDER + ".id",
                            scheduleOrders.stream().mapToLong(Entity::getId).boxed().collect(Collectors.toList())))
                    .setProjection(
                            list().add(alias(SearchProjections.groupField(OperationalTaskFields.ORDER + "." + OrderFields.NUMBER),
                                    OrderFields.NUMBER)))
                    .addOrder(desc(OperationalTaskFields.ORDER + "." + OrderFields.NUMBER)).list().getEntities();
            if (!orders.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Entity order : orders) {
                    sb.append(order.getStringField(OrderFields.NUMBER));
                    sb.append(", ");
                }
                entity.addGlobalError("orders.schedule.operationalTasks.wrongState", false, true, sb.toString());
            }
        }
    }

    @Override
    public Entity onAfterSave(Entity entity, String sourceState, String targetState, Entity stateChangeEntity,
            StateChangeEntityDescriber describer) {
        switch (targetState) {
            case ScheduleStateStringValues.APPROVED:
                generateOperationalTasks(entity);
                updateOrderDates(entity);
                break;

            case ScheduleStateStringValues.REJECTED:
                if (ScheduleStateStringValues.APPROVED.equals(sourceState)) {
                    operationalTaskOrderStateService.rejectOperationalTasksForSchedule(entity);
                }
        }

        return entity;
    }

    private void updateOrderDates(Entity entity) {
        List<Entity> orders = entity.getManyToManyField(ScheduleFields.ORDERS);
        for (Entity order : orders) {
            Date startTime = getOrderStartTime(entity, order);
            Date endTime = getOrderEndTime(entity, order);
            if (startTime != null || endTime != null) {
                String orderState = order.getStringField(OrderFields.STATE);
                setOrderDateFrom(order, startTime, orderState);
                setOrderDateTo(order, endTime, orderState);
                order.getDataDefinition().save(order);
            }
        }
    }

    private Date getOrderEndTime(Entity entity, Entity order) {
        Entity schedulePositionMaxEndTimeEntity = dataDefinitionService
                .get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_SCHEDULE_POSITION).find()
                .add(SearchRestrictions.belongsTo(SchedulePositionFields.ORDER, order))
                .add(SearchRestrictions.belongsTo(SchedulePositionFields.SCHEDULE, entity))
                .setProjection(
                        list().add(alias(SearchProjections.max(SchedulePositionFields.END_TIME), SchedulePositionFields.END_TIME))
                                .add(rowCount()))
                .addOrder(SearchOrders.desc(SchedulePositionFields.END_TIME)).setMaxResults(1).uniqueResult();
        return schedulePositionMaxEndTimeEntity.getDateField(SchedulePositionFields.END_TIME);
    }

    private Date getOrderStartTime(Entity entity, Entity order) {
        Entity schedulePositionMinStartTimeEntity = dataDefinitionService
                .get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_SCHEDULE_POSITION).find()
                .add(SearchRestrictions.belongsTo(SchedulePositionFields.ORDER, order))
                .add(SearchRestrictions.belongsTo(SchedulePositionFields.SCHEDULE, entity))
                .setProjection(list()
                        .add(alias(SearchProjections.min(SchedulePositionFields.START_TIME), SchedulePositionFields.START_TIME))
                        .add(rowCount()))
                .addOrder(SearchOrders.asc(SchedulePositionFields.START_TIME)).setMaxResults(1).uniqueResult();
        return schedulePositionMinStartTimeEntity.getDateField(SchedulePositionFields.START_TIME);
    }

    private void setOrderDateTo(Entity order, Date endTime, String orderState) {
        if (endTime != null) {
            if (OrderState.PENDING.getStringValue().equals(orderState)) {
                order.setField(OrderFields.DATE_TO, endTime);
            } else if ((OrderState.ACCEPTED.getStringValue().equals(orderState))) {
                order.setField(OrderFields.CORRECTED_DATE_TO, endTime);
            }
        }
    }

    private void setOrderDateFrom(Entity order, Date startTime, String orderState) {
        if (startTime != null) {
            if (OrderState.PENDING.getStringValue().equals(orderState)) {
                order.setField(OrderFields.DATE_FROM, startTime);
            } else if ((OrderState.ACCEPTED.getStringValue().equals(orderState))) {
                order.setField(OrderFields.CORRECTED_DATE_FROM, startTime);
            }
        }
    }

    private void generateOperationalTasks(Entity schedule) {
        DataDefinition operationalTaskDD = dataDefinitionService.get(OperationalTasksConstants.PLUGIN_IDENTIFIER,
                OperationalTasksConstants.MODEL_OPERATIONAL_TASK);
        for (Entity position : schedule.getHasManyField(ScheduleFields.POSITIONS)) {
            Entity operationalTask = operationalTaskDD.create();
            operationalTask.setField(OperationalTaskFields.NUMBER, numberGeneratorService.generateNumber(
                    OperationalTasksConstants.PLUGIN_IDENTIFIER, OperationalTasksConstants.MODEL_OPERATIONAL_TASK));
            operationalTask.setField(OperationalTaskFields.START_DATE, position.getField(SchedulePositionFields.START_TIME));
            operationalTask.setField(OperationalTaskFields.FINISH_DATE, position.getField(SchedulePositionFields.END_TIME));
            operationalTask.setField(OperationalTaskFields.TYPE,
                    OperationalTaskType.EXECUTION_OPERATION_IN_ORDER.getStringValue());
            Entity order = position.getBelongsToField(SchedulePositionFields.ORDER);
            operationalTask.setField(OperationalTaskFields.ORDER, order);
            Entity technologyOperationComponent = position
                    .getBelongsToField(SchedulePositionFields.TECHNOLOGY_OPERATION_COMPONENT);
            if (!technologyOperationComponent.getBooleanField(IS_SUBCONTRACTING)) {
                operationalTask.setField(OperationalTaskFields.PRODUCTION_LINE,
                        order.getBelongsToField(OrderFields.PRODUCTION_LINE));
            }

            operationalTask.setField(OperationalTaskFields.TECHNOLOGY_OPERATION_COMPONENT, technologyOperationComponent);

            operationalTask.setField(OperationalTaskFields.WORKSTATION, position.getField(SchedulePositionFields.WORKSTATION));
            operationalTask.setField(OperationalTaskFields.STAFF, position.getField(SchedulePositionFields.STAFF));
            operationalTask.setField(OperationalTaskFields.PRODUCT, position.getField(SchedulePositionFields.PRODUCT));
            operationalTask.setField(OperationalTaskFields.SCHEDULE_POSITION, position);

            operationalTaskDD.save(operationalTask);
        }
        schedule.addGlobalMessage("productionScheduling.operationDurationDetailsInOrder.info.operationalTasksCreated");
    }
}
