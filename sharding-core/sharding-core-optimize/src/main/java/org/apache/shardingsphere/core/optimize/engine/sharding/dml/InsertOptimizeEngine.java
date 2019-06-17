/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.core.optimize.engine.sharding.dml;

import com.google.common.base.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.core.optimize.GeneratedKey;
import org.apache.shardingsphere.core.optimize.condition.RouteCondition;
import org.apache.shardingsphere.core.optimize.condition.RouteConditions;
import org.apache.shardingsphere.core.optimize.engine.OptimizeEngine;
import org.apache.shardingsphere.core.optimize.result.OptimizeResult;
import org.apache.shardingsphere.core.optimize.result.insert.InsertOptimizeResult;
import org.apache.shardingsphere.core.optimize.result.insert.InsertOptimizeResultUnit;
import org.apache.shardingsphere.core.parse.sql.context.condition.AndCondition;
import org.apache.shardingsphere.core.parse.sql.context.condition.Condition;
import org.apache.shardingsphere.core.parse.sql.context.insertvalue.InsertValue;
import org.apache.shardingsphere.core.parse.sql.segment.dml.expr.ExpressionSegment;
import org.apache.shardingsphere.core.parse.sql.segment.dml.expr.simple.LiteralExpressionSegment;
import org.apache.shardingsphere.core.parse.sql.segment.dml.expr.simple.ParameterMarkerExpressionSegment;
import org.apache.shardingsphere.core.parse.sql.statement.dml.InsertStatement;
import org.apache.shardingsphere.core.rule.ShardingRule;
import org.apache.shardingsphere.core.strategy.route.value.ListRouteValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Insert optimize engine for sharding.
 *
 * @author zhangliang
 * @author maxiaoguang
 * @author panjuan
 */
@RequiredArgsConstructor
public final class InsertOptimizeEngine implements OptimizeEngine {
    
    private final ShardingRule shardingRule;
    
    private final InsertStatement insertStatement;
    
    private final List<Object> parameters;
    
    @Override
    public OptimizeResult optimize() {
        InsertOptimizeResult insertOptimizeResult = new InsertOptimizeResult(insertStatement.getColumnNames());
        List<AndCondition> andConditions = insertStatement.getShardingConditions().getOrConditions();
        List<RouteCondition> routeConditions = new ArrayList<>(andConditions.size());
        Optional<GeneratedKey> generatedKey = GeneratedKey.getGenerateKey(shardingRule, parameters, insertStatement);
        Iterator<Comparable<?>> generatedValues = generatedKey.isPresent() && generatedKey.get().isGenerated() ? generatedKey.get().getGeneratedValues().iterator() : null;
        int parametersCount = 0;
        for (int i = 0; i < andConditions.size(); i++) {
            InsertValue insertValue = insertStatement.getValues().get(i);
            boolean isGeneratedValue = generatedKey.isPresent() && generatedKey.get().isGenerated();
            ExpressionSegment[] currentInsertValues = createCurrentInsertValues(insertValue, isGeneratedValue);
            Object[] currentParameters = createCurrentParameters(insertValue, parametersCount, isGeneratedValue);
            parametersCount += insertValue.getParametersCount();
            RouteCondition routeCondition = createShardingCondition(andConditions.get(i));
            insertOptimizeResult.addUnit(currentInsertValues, currentParameters, insertValue.getParametersCount());
            if (generatedKey.isPresent() && generatedKey.get().isGenerated()) {
                Comparable<?> currentGeneratedKey = generatedValues.next();
                fillWithGeneratedKeyName(insertOptimizeResult);
                fillInsertOptimizeResultUnit(insertOptimizeResult.getUnits().get(i), currentGeneratedKey);
                fillRouteCondition(routeCondition, currentGeneratedKey);
            }
            if (isNeededToAppendQueryAssistedColumn()) {
                fillWithQueryAssistedColumn(insertOptimizeResult, i);
            }
            routeConditions.add(routeCondition);
        }
        OptimizeResult result = new OptimizeResult(new RouteConditions(routeConditions), insertOptimizeResult);
        if (generatedKey.isPresent()) {
            result.setGeneratedKey(generatedKey.get());
        }
        return result;
    }
    
    private ExpressionSegment[] createCurrentInsertValues(final InsertValue insertValue, final boolean isGeneratedValue) {
        ExpressionSegment[] result = new ExpressionSegment[insertValue.getAssignments().size() + getDerivedColumnsCount(isGeneratedValue)];
        insertValue.getAssignments().toArray(result);
        return result;
    }
    
    private Object[] createCurrentParameters(final InsertValue insertValue, final int parametersBeginIndex, final boolean isGeneratedValue) {
        if (0 == insertValue.getParametersCount()) {
            return new Object[0];
        }
        Object[] result = new Object[insertValue.getParametersCount() + getDerivedColumnsCount(isGeneratedValue)];
        parameters.subList(parametersBeginIndex, parametersBeginIndex + insertValue.getParametersCount()).toArray(result);
        return result;
    }
    
    private int getDerivedColumnsCount(final boolean isGeneratedValue) {
        int assistedQueryColumnsCount = shardingRule.getEncryptRule().getEncryptorEngine().getAssistedQueryColumnCount(insertStatement.getTables().getSingleTableName());
        return isGeneratedValue ? assistedQueryColumnsCount + 1 : assistedQueryColumnsCount;
    }
    
    private RouteCondition createShardingCondition(final AndCondition andCondition) {
        RouteCondition result = new RouteCondition();
        result.getRouteValues().addAll(getRouteValues(andCondition));
        return result;
    }
    
    private Collection<ListRouteValue> getRouteValues(final AndCondition andCondition) {
        Collection<ListRouteValue> result = new LinkedList<>();
        for (Condition each : andCondition.getConditions()) {
            result.add(new ListRouteValue<>(each.getColumn().getName(), each.getColumn().getTableName(), each.getConditionValues(parameters)));
        }
        return result;
    }
    
    private void fillWithGeneratedKeyName(final InsertOptimizeResult insertOptimizeResult) {
        String generateKeyColumnName = shardingRule.findGenerateKeyColumnName(insertStatement.getTables().getSingleTableName()).get();
        insertOptimizeResult.getColumnNames().add(generateKeyColumnName);
    }
    
    private void fillRouteCondition(final RouteCondition routeCondition, final Comparable<?> currentGeneratedKey) {
        String tableName = insertStatement.getTables().getSingleTableName();
        String generateKeyColumnName = shardingRule.findGenerateKeyColumnName(tableName).get();
        if (shardingRule.isShardingColumn(generateKeyColumnName, tableName)) {
            routeCondition.getRouteValues().add(new ListRouteValue<>(generateKeyColumnName, tableName, Collections.<Comparable<?>>singletonList(currentGeneratedKey)));
        }
    }
    
    private boolean isNeededToAppendQueryAssistedColumn() {
        return shardingRule.getEncryptRule().getEncryptorEngine().isHasShardingQueryAssistedEncryptor(insertStatement.getTables().getSingleTableName());
    }
    
    private void fillWithQueryAssistedColumn(final InsertOptimizeResult insertOptimizeResult, final int insertOptimizeResultIndex) {
        Collection<String> assistedColumnNames = new LinkedList<>();
        for (String each : insertOptimizeResult.getColumnNames()) {
            InsertOptimizeResultUnit unit = insertOptimizeResult.getUnits().get(insertOptimizeResultIndex);
            Optional<String> assistedColumnName = shardingRule.getEncryptRule().getEncryptorEngine().getAssistedQueryColumn(insertStatement.getTables().getSingleTableName(), each);
            if (assistedColumnName.isPresent()) {
                assistedColumnNames.add(assistedColumnName.get());
                fillInsertOptimizeResultUnit(unit, (Comparable<?>) unit.getColumnValue(each));
            }
        }
        if (!assistedColumnNames.isEmpty()) {
            insertOptimizeResult.getColumnNames().addAll(assistedColumnNames);
        }
    }
    
    private void fillInsertOptimizeResultUnit(final InsertOptimizeResultUnit unit, final Comparable<?> columnValue) {
        if (!parameters.isEmpty()) {
            // TODO fix start index and stop index
            unit.addColumnValue(new ParameterMarkerExpressionSegment(0, 0, parameters.size() - 1));
            unit.addColumnParameter(columnValue);
        } else {
            // TODO fix start index and stop index
            unit.addColumnValue(new LiteralExpressionSegment(0, 0, columnValue));
        }
    }
}