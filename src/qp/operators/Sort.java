package qp.operators;

import qp.utils.*;

import java.io.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The External Sort algorithm.
 */
public class Sort extends Operator {
    private static int uniqueFileNumber = 0;
    private static final String FILE_PREFIX = "ext_sort";
    private static final String UNSORTED_FILE = "ext-sort_unsorted";

    private final Operator base;
    private final Direction sortDirection;
    private final List<Attribute> sortAttributes;
    private final List<Integer> sortIndices;
    private final Comparator<Tuple> recordComparator;
    private final int bufferSize;
    private List<Batch> inputBuffer;

    private ObjectInputStream sortedRecordsInputStream;
    private boolean isEndOfStream;

    /**
     * Constructs an instance with an allocated buffer size.
     *
     * @param bufferSize The buffer size
     */
    public Sort(Operator base, List<Attribute> sortAttributes, Sort.Direction sortDirection, int bufferSize) {
        super(OperatorType.ORDER);
        this.base = base;
        this.schema = base.schema;
        this.sortDirection = sortDirection;
        this.sortAttributes = sortAttributes;
        this.sortIndices = computeSortIndices(this.base.getSchema(), this.sortAttributes);
        recordComparator = generateTupleComparator(this.sortDirection, this.sortIndices);

        this.bufferSize = bufferSize;
        inputBuffer = new ArrayList<>(bufferSize);
    }

    private List<Integer> computeSortIndices(Schema schema, List<Attribute> sortedAttributes) {
        List<Integer> sortIndices = new ArrayList<>();
        for (Attribute sortAttribute : sortedAttributes) {
            int sortIndex = schema.indexOf(sortAttribute);
            sortIndices.add(sortIndex);
        }
        return sortIndices;
    }

    private Comparator<Tuple> generateTupleComparator(Sort.Direction sortDirection, List<Integer> sortIndices) {
        switch (sortDirection) {
            case ASC:
                return (o1, o2) -> Tuple.compare(o1, o2, sortIndices);
            case DSC:
                return (o1, o2) -> Tuple.compare(o2, o1, sortIndices);
            default:
                throw new RuntimeException();
        }
    }

    @Override
    public boolean open() {
        if (!base.open()) {
            return false;
        }

        Batch page;
        int i = 0;
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(UNSORTED_FILE))) {

            while ((page = base.next()) != null) {
                //TODO for some reasons, some pages at the end are empty
                if (page.isEmpty()) {
                    continue;
                }
                i++;
                out.writeObject(page);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        List<String> sortedRuns = generateSortedRuns();
        while (sortedRuns.size() > 1) {
            sortedRuns = mergeSortedRuns(sortedRuns);
        }
        try {
            sortedRecordsInputStream = new ObjectInputStream(new FileInputStream(sortedRuns.get(0)));
            isEndOfStream = false;
        } catch (IOException e) {
            e.printStackTrace();
        }

        return base.close();
    }

    /**
     * Generates the initial sorted runs.
     *
     * @return The sorted runs
     */
    private List<String> generateSortedRuns() {
        List<String> sortedRuns = new ArrayList<>();

        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(UNSORTED_FILE))) {
            readIntoInputBuffer(in);

            do {
                List<Tuple> records = new ArrayList<>();
                for (Batch batch : inputBuffer) {
                    records.addAll(batch.getRecords());
                }
                records.sort(recordComparator);

                int tupleIndex = 0;
                while (tupleIndex < records.size()) {
                    for (int j = 0; j < inputBuffer.size(); j++) {
                        Batch page = inputBuffer.get(j);
                        for (int i = 0; i < page.size(); i++) {
                            Tuple record = records.get(tupleIndex++);
                            page.setRecord(record, i);
                            if (tupleIndex >= records.size()) {
                                inputBuffer = inputBuffer.subList(0, j + 1);
                                break;
                            }
                        }
                    }
                }

                int j = 0;
                String sortedRun = FILE_PREFIX + uniqueFileNumber++;
                try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(sortedRun))) {
                    for (Batch page : inputBuffer) {
                        j++;
                        out.writeObject(page);
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                }
                sortedRuns.add(sortedRun);
                readIntoInputBuffer(in);
            } while (!inputBuffer.isEmpty());

        } catch (IOException e) {
            e.printStackTrace();
        }

        return sortedRuns;
    }

    private void readIntoInputBuffer(ObjectInputStream in) {
        Batch page;
        int i = 0;
        inputBuffer.clear();
        try {
            while (inputBuffer.size() < bufferSize && (page = (Batch) in.readObject()) != null) {
                //TODO stopgap measure; not sure why page can be empty
                if (page.size() != 0) {
                    i++;
                    inputBuffer.add(page);
                }
            }
        } catch (EOFException e) {
            // do not read anymore
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    private List<String> mergeSortedRuns(List<String> sortedRuns) {
        List<ObjectInputStream> inputStreams = openInputConnections(sortedRuns);
        readIntoInputBuffer(inputStreams.toArray(new ObjectInputStream[0]));
        List<String> newSortedRuns = new ArrayList<>();
        do {

            Batch outputBuffer = new Batch(Batch.getPageSize() / schema.getTupleSize());
            String sortedRun = FILE_PREFIX + uniqueFileNumber++;
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(sortedRun))) {
                while (true) {
                    Object[] nextRecordToAdd = getFirstRecord();
                    if (nextRecordToAdd == null) {
                        break;
                    }
                    Tuple targetRecord = (Tuple) nextRecordToAdd[0];
                    int targetIndex = (int) (nextRecordToAdd[1]);
                    for (int i = 0; i < inputBuffer.size(); i++) {
                        Batch page = inputBuffer.get(i);
                        Tuple record = page.getNextRecord();
                        if (record == null) {
                            continue;
                        }
                        if (record != targetRecord) {
                            int result = Tuple.compare(targetRecord, record, this.sortIndices);
                            if (result < 0) {
                                switch (sortDirection) {
                                    case ASC:
                                        continue;
                                    case DSC:
                                        targetIndex = i;
                                        targetRecord = record;
                                        break;
                                    default:
                                        throw new RuntimeException();
                                }
                            }
                            if (result > 0) {
                                switch (sortDirection) {
                                    case ASC:
                                        targetIndex = i;
                                        targetRecord = record;
                                        break;
                                    case DSC:
                                        continue;
                                    default:
                                        throw new RuntimeException();
                                }
                            }
                        }
                    }

                    outputBuffer.addRecord(inputBuffer.get(targetIndex).removeAndGetFirst());
                    if (inputBuffer.get(targetIndex).isEmpty()) {
                        try {
                            Batch nextPage = (Batch) inputStreams.get(targetIndex).readObject();
                            if (nextPage != null) {
                                inputBuffer.set(targetIndex, nextPage);
                            }
                        } catch (EOFException e) {
                            // nothing left to read

                        } catch (ClassNotFoundException e) {
                            e.printStackTrace();
                        }
                    }
                    if (outputBuffer.isFull()) {
                        out.writeObject(outputBuffer);
                        outputBuffer.clearRecords();
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
            }


            newSortedRuns.add(sortedRun);
            readIntoInputBuffer(inputStreams.toArray(new ObjectInputStream[0]));
        } while (!inputBuffer.isEmpty());
        return newSortedRuns;
    }

    private List<ObjectInputStream> openInputConnections(List<String> sortedRuns) {
        List<ObjectInputStream> inputStreams = new ArrayList<>();
        try {
            for (String sortedRun : sortedRuns) {
                inputStreams.add(new ObjectInputStream(new FileInputStream(sortedRun)));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return inputStreams;
    }

    private void readIntoInputBuffer(ObjectInputStream... inStreams) {
        inputBuffer.clear();
        Batch page;
        for (ObjectInputStream in : inStreams) {
            try {
                while (inputBuffer.size() < bufferSize - 1 && (page = (Batch) in.readObject()) != null) {
                    inputBuffer.add(page);
                }
            } catch (EOFException e) {
                // nothing left to read
            } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    private Object[] getFirstRecord() {
        for (int i = 0; i < inputBuffer.size(); i++) {
            Batch page = inputBuffer.get(i);
            if (page.hasMore()) {
                return new Object[] {page.getNextRecord(), i};
            }
        }
        return null;
    }

    @Override
    public Batch next() {
        if (isEndOfStream) {
            return null;
        }

        try {
            return (Batch) sortedRecordsInputStream.readObject();
        } catch (EOFException | NullPointerException e) {
            isEndOfStream = true;
            return null;
            // do not read any more
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public boolean close() {
        try {
            sortedRecordsInputStream.close();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public Object clone() {
        List<Attribute> newSortAttributes = new ArrayList<>();
        for (Attribute attribute : sortAttributes) {
            newSortAttributes.add((Attribute) attribute.clone());
        }
        Sort newSort = new Sort((Operator) base.clone(), newSortAttributes, sortDirection, bufferSize);
        newSort.setSchema((Schema) base.getSchema().clone());
        return newSort;
    }

    public enum Direction {
        ASC, DSC
    }
}
