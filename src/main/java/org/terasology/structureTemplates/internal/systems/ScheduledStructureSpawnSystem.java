/*
 * Copyright 2016 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.structureTemplates.internal.systems;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.assets.ResourceUrn;
import org.terasology.entitySystem.entity.EntityBuilder;
import org.terasology.entitySystem.entity.EntityManager;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.entitySystem.entity.lifecycleEvents.BeforeRemoveComponent;
import org.terasology.entitySystem.entity.lifecycleEvents.OnAddedComponent;
import org.terasology.entitySystem.event.ReceiveEvent;
import org.terasology.entitySystem.prefab.Prefab;
import org.terasology.entitySystem.prefab.PrefabManager;
import org.terasology.entitySystem.systems.BaseComponentSystem;
import org.terasology.entitySystem.systems.RegisterMode;
import org.terasology.entitySystem.systems.RegisterSystem;
import org.terasology.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.logic.location.LocationComponent;
import org.terasology.math.Side;
import org.terasology.math.geom.Vector3i;
import org.terasology.registry.In;
import org.terasology.structureTemplates.components.PendingStructureSpawnComponent;
import org.terasology.structureTemplates.components.ScheduleStructurePlacementComponent;
import org.terasology.structureTemplates.components.StructureTemplateComponent;
import org.terasology.structureTemplates.events.CheckSpawnConditionEvent;
import org.terasology.structureTemplates.events.SpawnStructureEvent;
import org.terasology.structureTemplates.util.transform.BlockRegionMovement;
import org.terasology.structureTemplates.util.transform.BlockRegionTransform;
import org.terasology.structureTemplates.util.transform.BlockRegionTransformationList;
import org.terasology.structureTemplates.util.transform.HorizontalBlockRegionRotation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Powers the {@link ScheduleStructurePlacementComponent}. When a {@link SpawnStructureEvent} is received it creates
 * entities with the {@lin PendingStructureSpawnComponent} in order to cause the spawning of a prefab with the
 * {@link StructureTemplateComponent} at the wanted locations.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class ScheduledStructureSpawnSystem extends BaseComponentSystem implements UpdateSubscriberSystem {
    private static final Logger logger = LoggerFactory.getLogger(ScheduledStructureSpawnSystem.class);

    @In
    private EntityManager entityManager;

    private List<EntityRef> pendingSpawnEntities = new ArrayList<>();

    @In
    private PrefabManager prefabManager;

    private Random random = new Random();

    private Map<ResourceUrn, List<EntityRef>> structureTypeToEntitiesMap;

    @ReceiveEvent
    public void onScheduleStructurePlacement(SpawnStructureEvent event, EntityRef entity,
                                    ScheduleStructurePlacementComponent component) {

        BlockRegionTransform transformation = event.getTransformation();
        for (ScheduleStructurePlacementComponent.PlacementToSchedule placement: component.placementsToSchedule) {
                Side direction =  transformation.transformSide(placement.front);
                Vector3i position = transformation.transformVector3i(placement.position);
                EntityBuilder entityBuilder = entityManager.newBuilder();
                LocationComponent locationComponent = new LocationComponent();
                locationComponent.setWorldPosition(position.toVector3f());
                entityBuilder.addComponent(locationComponent);
            if (placement.structureTemplateType == null) {
                logger.error("ScheduleStructurePlacement component in prefab %s has no (valid) structureTemplateType value");
                continue;
            }

            PendingStructureSpawnComponent pendingStructureSpawnComponent = new PendingStructureSpawnComponent();
            pendingStructureSpawnComponent.front = direction;
            pendingStructureSpawnComponent.structureTemplateType = placement.structureTemplateType;
            entityBuilder.addComponent(pendingStructureSpawnComponent);
            entityBuilder.build();
        }
    }

    @Override
    public void postBegin() {
        Iterable<Prefab> prefabs = prefabManager.listPrefabs(StructureTemplateComponent.class);
        structureTypeToEntitiesMap = Maps.newHashMap();
        for (Prefab prefab: prefabs) {
            StructureTemplateComponent component = prefab.getComponent(StructureTemplateComponent.class);
            Prefab structureTypePrefab = component.type;
            if (structureTypePrefab == null) {
                continue;
            }
            EntityBuilder entityBuilder = entityManager.newBuilder(prefab);
            entityBuilder.setPersistent(false);
            EntityRef entity = entityBuilder.build();
            List<EntityRef> entities = structureTypeToEntitiesMap.get(structureTypePrefab.getUrn());
            if (entities == null) {
                entities = Lists.newArrayList();
                structureTypeToEntitiesMap.put(structureTypePrefab.getUrn(), entities);
            }
            entities.add(entity);
        }
    }

    @ReceiveEvent
    public void onAddedPendingStructureSpawnComponent(OnAddedComponent event, EntityRef entity,
                                                      PendingStructureSpawnComponent component,
                                                      LocationComponent locationComponent) {
        pendingSpawnEntities.add(entity);
    }

    @ReceiveEvent
    public void onBeforeRemovePendingStructureSpawnComponent(BeforeRemoveComponent event, EntityRef entity,
                                                             PendingStructureSpawnComponent component,
                                                             LocationComponent locationComponent) {
        pendingSpawnEntities.remove(entity);
    }


    @Override
    public void update(float delta) {
        if (pendingSpawnEntities.size() == 0) {
            return;
        }

        EntityRef randomEntity = pendingSpawnEntities.get(pendingSpawnEntities.size()-1);
        PendingStructureSpawnComponent pendingStructureSpawnComponent = randomEntity.getComponent(
                PendingStructureSpawnComponent.class);
        LocationComponent locationComponent = randomEntity.getComponent(LocationComponent.class);
        if (pendingStructureSpawnComponent == null || locationComponent == null) {
            return; // should not happen though how map gets filled, but just to be sure
        }
        Prefab type = pendingStructureSpawnComponent.structureTemplateType;
        Side direction = pendingStructureSpawnComponent.front;

        List<EntityRef> possibleStructures = structureTypeToEntitiesMap.get(type.getUrn());

        if (possibleStructures == null ||  possibleStructures.size() == 0) {
            return;
        }
        EntityRef structureToSpawn = possibleStructures.get(random.nextInt(possibleStructures.size()));
        
        StructureTemplateComponent structureTemplateComponent = structureToSpawn.getComponent(
                StructureTemplateComponent.class);
        Vector3i spawnPosition = new Vector3i(locationComponent.getWorldPosition());
        Vector3i relSpawnPosition = new Vector3i(structureTemplateComponent.spawnPosition);
        Side front = structureTemplateComponent.front;

        BlockRegionTransformationList transformList = createTransformForIncomingConnectionPoint(direction,
                spawnPosition, relSpawnPosition, front);

        CheckSpawnConditionEvent checkSpawnConditionEvent = new CheckSpawnConditionEvent(transformList);
        structureToSpawn.send(checkSpawnConditionEvent);
        if (checkSpawnConditionEvent.isPreventSpawn()) {
            return;
        }
        structureToSpawn.send(new SpawnStructureEvent(transformList));

        randomEntity.destroy();
    }

    static BlockRegionTransformationList createTransformForIncomingConnectionPoint(Side direction, Vector3i spawnPosition, Vector3i incomingConnectionPointPosition, Side incomingConnectionPointDirection) {
        HorizontalBlockRegionRotation rot = HorizontalBlockRegionRotation.createRotationFromSideToSide(
                incomingConnectionPointDirection, direction);
        Vector3i tranformedOffset = rot.transformVector3i(incomingConnectionPointPosition);
        Vector3i actualSpawnPosition = new Vector3i(spawnPosition);
        actualSpawnPosition.sub(tranformedOffset);

        BlockRegionTransformationList transformList = new BlockRegionTransformationList();
        transformList.addTransformation(
                HorizontalBlockRegionRotation.createRotationFromSideToSide(incomingConnectionPointDirection, direction));
        transformList.addTransformation(new BlockRegionMovement(actualSpawnPosition));
        return transformList;
    }
}
