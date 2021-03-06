/* (c) 2014 LinkedIn Corp. All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */

package com.linkedin.cubert.operator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;

import com.linkedin.cubert.block.Block;
import com.linkedin.cubert.block.BlockProperties;
import com.linkedin.cubert.block.BlockSchema;
import com.linkedin.cubert.block.ColumnType;
import com.linkedin.cubert.utils.JsonUtils;
import com.linkedin.cubert.utils.MemoryStats;
import com.linkedin.cubert.utils.SerializedTupleStore;
import com.linkedin.cubert.utils.print;

public class HashJoinOperator implements TupleOperator
{
    private Map<Tuple, List<Tuple>> rightBlockHashTable;

    private Block leftBlock;
    private Block rightBlock;
    private Tuple leftTuple = null;
    private Tuple keyTuple;
    private RightTupleList matchedRightTupleList = new RightTupleList(0);

    private SerializedTupleStore mySerializedStore = null;

    /*
     * SingleNullTupleList, which is a singleton in the context of operator and contains
     * one all-null right tuple.
     */
    private RightTupleList singleNULLTupleList;
    private final RightTupleList emptyTupleList = new RightTupleList(0);

    private String leftName;
    private String rightName;

    private int[] leftJoinColumnIndex;
    private int[] rightJoinColumnIndex;

    private RightTupleList rightTupleList;

    int outputCounter = 0;

    Tuple output;
    BlockSchema schema;

    String[] leftBlockColumns = null;
    String[] rightBlockColumns = null;

    private boolean isLeftJoin = false;
    private boolean isRightJoin = false;

    private Set<Object> matchedRightKeySet;

    private boolean initUnmatchedRightTupleOutput;

    private Iterator<Tuple> unmatchedRightKeyIterator;

    private boolean isLeftBlockExhausted = false;

    private RightTupleList unMatchedRightTupleList;

    private static final String JOIN_CHARACTER = "___";
    private static final String JOIN_TYPE_STR = "joinType";
    private static final String LEFT_OUTER_JOIN = "LEFT OUTER";
    private static final String RIGHT_OUTER_JOIN = "RIGHT OUTER";

    @Override
    public void setInput(Map<String, Block> input, JsonNode root, BlockProperties props) throws JsonParseException,
            JsonMappingException,
            IOException,
            InterruptedException
    {
        String leftBlockName = JsonUtils.getText(root, "leftBlock");

        for (String name : input.keySet())
        {
            if (name.equalsIgnoreCase(leftBlockName))
            {
                leftName = name;
                leftBlock = input.get(name);
            }
            else
            {
                rightName = name;
                rightBlock = input.get(name);
            }
        }

        if (rightBlock == null)
            throw new RuntimeException("RIGHT block is null for join");
        if (leftBlock == null)
            throw new RuntimeException("LEFT block is null for join");

        BlockSchema leftSchema = leftBlock.getProperties().getSchema();
        BlockSchema rightSchema = rightBlock.getProperties().getSchema();

        if (root.has("joinKeys"))
        {
            leftBlockColumns = rightBlockColumns = JsonUtils.asArray(root, "joinKeys");
        }
        else
        {
            leftBlockColumns = JsonUtils.asArray(root, "leftJoinKeys");
            rightBlockColumns = JsonUtils.asArray(root, "rightJoinKeys");
        }

        leftJoinColumnIndex = new int[leftBlockColumns.length];
        rightJoinColumnIndex = new int[rightBlockColumns.length];

        for (int i = 0; i < leftBlockColumns.length; i++)
        {
            leftJoinColumnIndex[i] = leftSchema.getIndex(leftBlockColumns[i]);
            rightJoinColumnIndex[i] = rightSchema.getIndex(rightBlockColumns[i]);
        }

        // this is just keyTuple object that's reused for join key lookup.
        keyTuple = TupleFactory.getInstance().newTuple(leftBlockColumns.length);

        rightTupleList = new RightTupleList();

        if (root.has(JOIN_TYPE_STR))
        {
            if (JsonUtils.getText(root, JOIN_TYPE_STR).equalsIgnoreCase(LEFT_OUTER_JOIN))
            {
                isLeftJoin = true;
            }
            else if (JsonUtils.getText(root, JOIN_TYPE_STR)
                              .equalsIgnoreCase(RIGHT_OUTER_JOIN))
            {
                isRightJoin = true;
                matchedRightKeySet = new HashSet<Object>();
            }
        }

        /*
         * init the SingleNullTupleList, which is a singleton in the context of operator
         * and contains one all-null right tuple.
         */
        singleNULLTupleList = new RightTupleList();
        Tuple nullTuple =
                TupleFactory.getInstance().newTuple(rightSchema.getNumColumns());
        singleNULLTupleList.add(nullTuple);

        output = TupleFactory.getInstance().newTuple(props.getSchema().getNumColumns());

        MemoryStats.print("HASH JOIN OPERATOR before creating hashtable");
        long startTime = System.currentTimeMillis();
        createHashTable();
        long duration = System.currentTimeMillis() - startTime;
        print.f("Hashtable with %d entries creates in %d ms",
                this.rightBlockHashTable.size(),
                duration);
        MemoryStats.print("HASH JOIN OPERATOR after creating hashtable");
    }

    @Override
    public Tuple next() throws IOException,
            InterruptedException
    {
        outputCounter++;
        if (outputCounter % 1000 == 0)
        {
            PhaseContext.getCounter("hashjoinoperator", "outputCounter")
                        .increment(outputCounter);
            outputCounter = 0;
        }

        return getNextRow();
    }

    private RightTupleList getMatchedRightTupleList(Tuple leftTuple) throws IOException,
            InterruptedException
    {
        assert (leftTuple != null);

        // the projected
        keyTuple = getProjectedKeyTuple(leftTuple, leftJoinColumnIndex, true);

        List<Tuple> arrayList = rightBlockHashTable.get(keyTuple);

        if (arrayList == null)
        {
            rightTupleList = isLeftJoin ? singleNULLTupleList : emptyTupleList;
        }
        else
        {
            rightTupleList = new RightTupleList();
            rightTupleList.addAll(arrayList);
        }
        rightTupleList.rewind();

        if (arrayList != null && isRightJoin)
        {
            matchedRightKeySet.add(keyTuple);
        }

        return rightTupleList;
    }

    Tuple constructJoinTuple(Tuple leftTuple, Tuple rightTuple) throws ExecException
    {
        int idx = 0;
        for (Object field : leftTuple.getAll())
        {
            output.set(idx++, field);
        }
        for (Object field : rightTuple.getAll())
        {
            output.set(idx++, field);
        }

        return output;
    }

    private Tuple getNextRow() throws IOException,
            InterruptedException
    {

        while (!isLeftBlockExhausted && matchedRightTupleList.isExhausted())
        {
            leftTuple = leftBlock.next();

            if (leftTuple == null)
            {
                isLeftBlockExhausted = true;
            }
            else
            {
                matchedRightTupleList = getMatchedRightTupleList(leftTuple);
            }
        }

        if (!isLeftBlockExhausted)
        {
            return constructJoinTuple(leftTuple, matchedRightTupleList.getNextTuple());
        }
        else if (isRightJoin)
        {
            // left block exhausted
            // output those un-matched right tuples
            if (!initUnmatchedRightTupleOutput)
            {
                initUnmatchedRightTupleOutput = true;
                Set<Tuple> keySet = rightBlockHashTable.keySet();
                keySet.removeAll(matchedRightKeySet);
                unmatchedRightKeyIterator = keySet.iterator();
                if (!unmatchedRightKeyIterator.hasNext())
                    return null;

                Object key = unmatchedRightKeyIterator.next();

                RightTupleList l = new RightTupleList();
                l.addAll(rightBlockHashTable.get(key));

                unMatchedRightTupleList = l;
            }

            if (unMatchedRightTupleList.isExhausted())
            {

                if (!unmatchedRightKeyIterator.hasNext())
                    return null;

                Object key = unmatchedRightKeyIterator.next();
                RightTupleList l = new RightTupleList();
                l.addAll(rightBlockHashTable.get(key));
                unMatchedRightTupleList = l;
            }

            return constructJoinTuple(singleNULLTupleList.get(0),
                                      unMatchedRightTupleList.getNextTuple());

        }
        else
        {
            return null;
        }
    }

    // The projected keyTuple schema should be the same as that of the JoinKeys
    // New objects are created during hashTable creation, but during other times objects
    // must be reused
    Tuple getProjectedKeyTuple(Tuple inputTuple, int[] indices, boolean makeNewObject) throws ExecException
    {
        Tuple tempTuple;

        if (makeNewObject)
            tempTuple = TupleFactory.getInstance().newTuple(leftBlockColumns.length);
        else
            tempTuple = keyTuple;

        for (int i = 0; i < indices.length; i++)
            tempTuple.set(i, inputTuple.get(indices[i]));

        return tempTuple;
    }

    private void createHashTable() throws IOException,
            InterruptedException
    {
        mySerializedStore = null;
        mySerializedStore =
                new SerializedTupleStore(rightBlock.getProperties().getSchema(),
                                         rightBlockColumns);

        Tuple t;

        while ((t = rightBlock.next()) != null)
        {
            mySerializedStore.addToStore(t);
        }
        rightBlockHashTable = mySerializedStore.getHashTable();
    }

    @SuppressWarnings("serial")
    private class RightTupleList extends ArrayList<Tuple>
    {

        private int currentTuplePosition = 0;

        public RightTupleList()
        {
        }

        public RightTupleList(int initCapacity)
        {
            super(initCapacity);
        }

        public void rewind()
        {
            currentTuplePosition = 0;
        }

        public Tuple getNextTuple()
        {
            return this.get(currentTuplePosition++);
        }

        public boolean isExhausted()
        {
            return currentTuplePosition == this.size();
        }
    }

    @Override
    public PostCondition getPostCondition(Map<String, PostCondition> preConditions,
                                          JsonNode json) throws PreconditionException
    {
        // get the conditions of input blocks
        String leftBlockName = JsonUtils.getText(json, "leftBlock");
        PostCondition leftCondition = preConditions.get(leftBlockName);
        preConditions.remove(leftBlockName);
        if (preConditions.isEmpty())
            throw new PreconditionException(PreconditionExceptionType.INPUT_BLOCK_NOT_FOUND,
                                            "Only one input block is specified");
        String rightBlockName = preConditions.keySet().iterator().next();
        PostCondition rightCondition = preConditions.get(rightBlockName);

        // validate that the number of join keys are same
        if (json.has("joinKeys"))
        {
            leftBlockColumns = rightBlockColumns = JsonUtils.asArray(json, "joinKeys");
        }
        else
        {
            leftBlockColumns = JsonUtils.asArray(json, "leftJoinKeys");
            rightBlockColumns = JsonUtils.asArray(json, "rightJoinKeys");
        }
        if (leftBlockColumns.length != rightBlockColumns.length)
            throw new RuntimeException("The number of join keys in the left and the right blocks do not match");

        // create block schema
        BlockSchema leftSchema = leftCondition.getSchema();
        BlockSchema rightSchema = rightCondition.getSchema();

        ColumnType[] joinedTypes =
                new ColumnType[leftSchema.getNumColumns() + rightSchema.getNumColumns()];
        int idx = 0;
        for (int i = 0; i < leftSchema.getNumColumns(); i++)
        {
            ColumnType leftColType = leftSchema.getColumnType(i);
            ColumnType type = new ColumnType();
            type.setName(leftBlockName + JOIN_CHARACTER + leftColType.getName());
            type.setType(leftColType.getType());
            type.setColumnSchema(leftColType.getColumnSchema());

            joinedTypes[idx++] = type;
        }

        for (int i = 0; i < rightSchema.getNumColumns(); i++)
        {
            ColumnType rightColType = rightSchema.getColumnType(i);
            ColumnType type = new ColumnType();
            type.setName(rightBlockName + JOIN_CHARACTER + rightColType.getName());
            type.setType(rightColType.getType());
            type.setColumnSchema(rightColType.getColumnSchema());

            joinedTypes[idx++] = type;
        }

        BlockSchema outputSchema = new BlockSchema(joinedTypes);

        final String[] sortKeys = leftCondition.getSortKeys();
        String[] joinedSortKeys = new String[sortKeys != null ? sortKeys.length : 0];
        for (int i = 0; i < joinedSortKeys.length; i++)
        {
            joinedSortKeys[i] = leftBlockName + JOIN_CHARACTER + sortKeys[i];
        }

        String[] partitionKeys = null;
        if (leftCondition.getPartitionKeys() != null)
        {
            partitionKeys = new String[leftCondition.getPartitionKeys().length];
            for (int i = 0; i < partitionKeys.length; i++)
                partitionKeys[i] =
                        leftBlockName + JOIN_CHARACTER
                                + leftCondition.getPartitionKeys()[i];
        }

        return new PostCondition(outputSchema, partitionKeys, joinedSortKeys);
    }
}
