import ai.djl.ndarray.*;
import ai.djl.ndarray.types.*;
import ai.djl.ndarray.index.*;
import ai.djl.util.*;
import ai.djl.*;
import ai.djl.engine.*;
import ai.djl.nn.*;
import ai.djl.nn.core.*;
import ai.djl.nn.recurrent.*;
import ai.djl.training.*;
import ai.djl.training.loss.*;
import ai.djl.training.tracker.*;
import ai.djl.training.initializer.*;
import ai.djl.training.evaluator.*;
import ai.djl.training.optimizer.*;
import ai.djl.training.listener.*;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.function.*;

public class Vocab {
    public int unk;
    public List<Map.Entry<String, Integer>> tokenFreqs;
    public List<String> idxToToken;
    public HashMap<String, Integer> tokenToIdx;

    public Vocab(String[][] tokens, int minFreq, String[] reservedTokens) {
        // Sort according to frequencies
        HashMap<String, Integer> counter = countCorpus2D(tokens);
        this.tokenFreqs = new ArrayList<Map.Entry<String, Integer>>(counter.entrySet());
        Collections.sort(
                tokenFreqs,
                new Comparator<Map.Entry<String, Integer>>() {
                    public int compare(
                            Map.Entry<String, Integer> o1, Map.Entry<String, Integer> o2) {
                        return (o2.getValue()).compareTo(o1.getValue());
                    }
                });

        // The index for the unknown token is 0
        this.unk = 0;
        List<String> uniqTokens = new ArrayList<>();
        uniqTokens.add("<unk>");
        Collections.addAll(uniqTokens, reservedTokens);
        for (Map.Entry<String, Integer> entry : tokenFreqs) {
            if (entry.getValue() >= minFreq && !uniqTokens.contains(entry.getKey())) {
                uniqTokens.add(entry.getKey());
            }
        }

        this.idxToToken = new ArrayList<>();
        this.tokenToIdx = new HashMap<>();
        for (String token : uniqTokens) {
            this.idxToToken.add(token);
            this.tokenToIdx.put(token, this.idxToToken.size() - 1);
        }
    }

    public int length() {
        return this.idxToToken.size();
    }

    public Integer[] getIdxs(String[] tokens) {
        List<Integer> idxs = new ArrayList<>();
        for (String token : tokens) {
            idxs.add(getIdx(token));
        }
        return idxs.toArray(new Integer[0]);
    }

    public Integer getIdx(String token) {
        return this.tokenToIdx.getOrDefault(token, this.unk);
    }

    /** Count token frequencies. */
    public HashMap<String, Integer> countCorpus(String[] tokens) {

        HashMap<String, Integer> counter = new HashMap<>();
        if (tokens.length != 0) {
            for (String token : tokens) {
                counter.put(token, counter.getOrDefault(token, 0) + 1);
            }
        }
        return counter;
    }

    /** Flatten a list of token lists into a list of tokens */
    public HashMap<String, Integer> countCorpus2D(String[][] tokens) {
        List<String> allTokens = new ArrayList<String>();
        for (int i = 0; i < tokens.length; i++) {
            for (int j = 0; j < tokens[i].length; j++) {
                if (tokens[i][j] != "") {
                    allTokens.add(tokens[i][j]);
                }
            }
        }
        return countCorpus(allTokens.toArray(new String[0]));
    }
}

public class SeqDataLoader implements Iterable<NDList> {
    public ArrayList<NDList> dataIter;
    public List<Integer> corpus;
    public Vocab vocab;
    public int batchSize;
    public int numSteps;

    /* An iterator to load sequence data. */
    @SuppressWarnings("unchecked")
    public SeqDataLoader(
            int batchSize, int numSteps, boolean useRandomIter, int maxTokens, NDManager manager)
            throws IOException, Exception {
        Pair<List<Integer>, Vocab> corpusVocabPair = TimeMachine.loadCorpusTimeMachine(maxTokens);
        this.corpus = corpusVocabPair.getKey();
        this.vocab = corpusVocabPair.getValue();

        this.batchSize = batchSize;
        this.numSteps = numSteps;
        if (useRandomIter) {
            dataIter = seqDataIterRandom(corpus, batchSize, numSteps, manager);
        } else {
            dataIter = seqDataIterSequential(corpus, batchSize, numSteps, manager);
        }
    }

    @Override
    public Iterator<NDList> iterator() {
        return dataIter.iterator();
    }

    /** Return the iterator and the vocabulary of the time machine dataset. */
    public static Pair<ArrayList<NDList>, Vocab> loadDataTimeMachine(
            int batchSize, int numSteps, boolean useRandomIter, int maxTokens, NDManager manager)
            throws IOException, Exception {

        SeqDataLoader seqData =
                new SeqDataLoader(batchSize, numSteps, useRandomIter, maxTokens, manager);
        return new Pair(seqData.dataIter, seqData.vocab); // ArrayList<NDList>, Vocab
    }

    /** Generate a minibatch of subsequences using random sampling. */
    public ArrayList<NDList> seqDataIterRandom(
            List<Integer> corpus, int batchSize, int numSteps, NDManager manager) {
        // Start with a random offset (inclusive of `numSteps - 1`) to partition a
        // sequence
        corpus = corpus.subList(new Random().nextInt(numSteps - 1), corpus.size());
        // Subtract 1 since we need to account for labels
        int numSubseqs = (corpus.size() - 1) / numSteps;
        // The starting indices for subsequences of length `numSteps`
        List<Integer> initialIndices = new ArrayList<>();
        for (int i = 0; i < numSubseqs * numSteps; i += numSteps) {
            initialIndices.add(i);
        }
        // In random sampling, the subsequences from two adjacent random
        // minibatches during iteration are not necessarily adjacent on the
        // original sequence
        Collections.shuffle(initialIndices);

        int numBatches = numSubseqs / batchSize;

        ArrayList<NDList> pairs = new ArrayList<NDList>();
        for (int i = 0; i < batchSize * numBatches; i += batchSize) {
            // Here, `initialIndices` contains randomized starting indices for
            // subsequences
            List<Integer> initialIndicesPerBatch = initialIndices.subList(i, i + batchSize);

            NDArray xNDArray =
                    manager.create(
                            new Shape(initialIndicesPerBatch.size(), numSteps), DataType.FLOAT32);
            NDArray yNDArray =
                    manager.create(
                            new Shape(initialIndicesPerBatch.size(), numSteps), DataType.FLOAT32);
            for (int j = 0; j < initialIndicesPerBatch.size(); j++) {
                ArrayList<Integer> X = data(initialIndicesPerBatch.get(j), corpus, numSteps);
                xNDArray.set(
                        new NDIndex(j),
                        manager.create(X.stream().mapToInt(Integer::intValue).toArray()));
                ArrayList<Integer> Y = data(initialIndicesPerBatch.get(j) + 1, corpus, numSteps);
                yNDArray.set(
                        new NDIndex(j),
                        manager.create(Y.stream().mapToInt(Integer::intValue).toArray()));
            }
            NDList pair = new NDList();
            pair.add(xNDArray);
            pair.add(yNDArray);
            pairs.add(pair);
        }
        return pairs;
    }

    ArrayList<Integer> data(int pos, List<Integer> corpus, int numSteps) {
        // Return a sequence of length `numSteps` starting from `pos`
        return new ArrayList<Integer>(corpus.subList(pos, pos + numSteps));
    }

    /** Generate a minibatch of subsequences using sequential partitioning. */
    public ArrayList<NDList> seqDataIterSequential(
            List<Integer> corpus, int batchSize, int numSteps, NDManager manager) {
        // Start with a random offset to partition a sequence
        int offset = new Random().nextInt(numSteps);
        int numTokens = ((corpus.size() - offset - 1) / batchSize) * batchSize;

        NDArray Xs =
                manager.create(
                        corpus.subList(offset, offset + numTokens).stream()
                                .mapToInt(Integer::intValue)
                                .toArray());
        NDArray Ys =
                manager.create(
                        corpus.subList(offset + 1, offset + 1 + numTokens).stream()
                                .mapToInt(Integer::intValue)
                                .toArray());
        Xs = Xs.reshape(new Shape(batchSize, -1));
        Ys = Ys.reshape(new Shape(batchSize, -1));
        int numBatches = (int) Xs.getShape().get(1) / numSteps;

        ArrayList<NDList> pairs = new ArrayList<NDList>();
        for (int i = 0; i < numSteps * numBatches; i += numSteps) {
            NDArray X = Xs.get(new NDIndex(":, {}:{}", i, i + numSteps));
            NDArray Y = Ys.get(new NDIndex(":, {}:{}", i, i + numSteps));
            NDList pair = new NDList();
            pair.add(X);
            pair.add(Y);
            pairs.add(pair);
        }
        return pairs;
    }
}

/** An RNN Model implemented from scratch. */
public class RNNModelScratch {
    public int vocabSize;
    public int numHiddens;
    public NDList params;
    public Functions.TriFunction<Integer, Integer, Device, NDArray> initState;
    public Functions.TriFunction<NDArray, NDArray, NDList, Pair> forwardFn;

    public RNNModelScratch(
            int vocabSize,
            int numHiddens,
            Device device,
            Functions.TriFunction<Integer, Integer, Device, NDList> getParams,
            Functions.TriFunction<Integer, Integer, Device, NDArray> initRNNState,
            Functions.TriFunction<NDArray, NDArray, NDList, Pair> forwardFn) {
        this.vocabSize = vocabSize;
        this.numHiddens = numHiddens;
        this.params = getParams.apply(vocabSize, numHiddens, device);
        this.initState = initRNNState;
        this.forwardFn = forwardFn;
    }

    public Pair call(NDArray X, NDArray state) {
        X = X.transpose().oneHot(this.vocabSize);
        return this.forwardFn.apply(X, state, this.params);
    }

    public NDArray beginState(int batchSize, Device device) {
        return this.initState.apply(batchSize, this.numHiddens, device);
    }
}

public class RNNModel extends AbstractBlock {

    private static final byte VERSION = 2;
    private RNN rnnLayer;
    private Linear dense;
    private int vocabSize;

    public RNNModel(RNN rnnLayer, int vocabSize) {
        super(VERSION);
        this.rnnLayer = rnnLayer;
        this.addChildBlock("rnn", rnnLayer);
        this.vocabSize = vocabSize;
        this.dense = Linear.builder().setUnits(vocabSize).build();
    }

    /** {@inheritDoc} */
    @Override
    public void initializeChildBlocks(NDManager manager, DataType dataType, Shape... inputShapes) {
        /* rnnLayer is already initialized so we don't have to do anything here, just override it.*/
        return;
    }

    @Override
    protected NDList forwardInternal(ParameterStore parameterStore, NDList inputs, boolean training, PairList<String, Object> params) {
        NDArray X = inputs.get(0).transpose().oneHot(this.vocabSize);
        inputs.set(0, X);
        NDList result = this.rnnLayer.forward(parameterStore, inputs, training);
        NDArray Y = result.get(0);
        NDArray state = result.get(1);

        int shapeLength = Y.getShape().getShape().length;
        NDList output = this.dense.forward(parameterStore, new NDList(Y
                .reshape(new Shape(-1, Y.getShape().get(shapeLength-1)))), training);
        return new NDList(output.get(0), state);
    }


    /* We won't implement this since we won't be using it but it's required as part of an AbstractBlock  */
    @Override
    public Shape[] getOutputShapes(Shape[] inputShapes) {
        return new Shape[0];
    }
}

public class TimeMachine {
    /** Split text lines into word or character tokens. */
    public static String[][] tokenize(String[] lines, String token) throws Exception {
        String[][] output = new String[lines.length][];
        if (token == "word") {
            for (int i = 0; i < output.length; i++) {
                output[i] = lines[i].split(" ");
            }
        } else if (token == "char") {
            for (int i = 0; i < output.length; i++) {
                output[i] = lines[i].split("");
            }
        } else {
            throw new Exception("ERROR: unknown token type: " + token);
        }
        return output;
    }

    /** Read `The Time Machine` dataset and return an array of the lines */
    public static String[] readTimeMachine() throws IOException {
        URL url = new URL("http://d2l-data.s3-accelerate.amazonaws.com/timemachine.txt");
        String[] lines;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()))) {
            lines = in.lines().toArray(String[]::new);
        }

        for (int i = 0; i < lines.length; i++) {
            lines[i] = lines[i].replaceAll("[^A-Za-z]+", " ").strip().toLowerCase();
        }
        return lines;
    }

    /** Return token indices and the vocabulary of the time machine dataset. */
    public static Pair<List<Integer>, Vocab> loadCorpusTimeMachine(int maxTokens)
            throws IOException, Exception {
        String[] lines = readTimeMachine();
        String[][] tokens = tokenize(lines, "char");
        Vocab vocab = new Vocab(tokens, 0, new String[0]);
        // Since each text line in the time machine dataset is not necessarily a
        // sentence or a paragraph, flatten all the text lines into a single list
        List<Integer> corpus = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            for (int j = 0; j < tokens[i].length; j++) {
                if (tokens[i][j] != "") {
                    corpus.add(vocab.getIdx(tokens[i][j]));
                }
            }
        }
        if (maxTokens > 0) {
            corpus = corpus.subList(0, maxTokens);
        }
        return new Pair(corpus, vocab);
    }

    /** Generate new characters following the `prefix`. */
    public static String predictCh8(
            String prefix,
            int numPreds,
            Object net,
            Vocab vocab,
            Device device,
            NDManager manager) {

        List<Integer> outputs = new ArrayList<>();
        outputs.add(vocab.getIdx("" + prefix.charAt(0)));
        Functions.SimpleFunction<NDArray> getInput =
                () ->
                        manager.create(outputs.get(outputs.size() - 1))
                                .toDevice(device, false)
                                .reshape(new Shape(1, 1));

        if (net instanceof RNNModelScratch) {
            RNNModelScratch castedNet = (RNNModelScratch) net;
            NDArray state = castedNet.beginState(1, device);

            for (char c : prefix.substring(1).toCharArray()) { // Warm-up period
                state = (NDArray) castedNet.call(getInput.apply(), state).getValue();
                outputs.add(vocab.getIdx("" + c));
            }

            NDArray y;
            for (int i = 0; i < numPreds; i++) {
                Pair<NDArray, NDArray> pair = castedNet.call(getInput.apply(), state);
                y = pair.getKey();
                state = pair.getValue();

                outputs.add((int) y.argMax(1).reshape(new Shape(1)).getLong(0L));
            }
        } else {
            RNNModel castedNet = (RNNModel) net;
            NDArray state = null;
            for (char c : prefix.substring(1).toCharArray()) { // Warm-up period
                if (state == null) {
                    // Begin state
                    state =
                            castedNet
                                    .forwardInternal(
                                            new ParameterStore(manager, false),
                                            new NDList(getInput.apply()),
                                            false,
                                            null)
                                    .get(1);
                } else {
                    state =
                            castedNet
                                    .forwardInternal(
                                            new ParameterStore(manager, false),
                                            new NDList(getInput.apply(), state),
                                            false,
                                            null)
                                    .get(1);
                }
                outputs.add(vocab.getIdx("" + c));
            }

            NDArray y;
            for (int i = 0; i < numPreds; i++) {
                NDList pair =
                        castedNet.forwardInternal(
                                new ParameterStore(manager, false),
                                new NDList(getInput.apply(), state),
                                false,
                                null);
                y = pair.get(0);
                state = pair.get(1);

                outputs.add((int) y.argMax(1).reshape(new Shape(1)).getLong(0L));
            }
        }

        String outputString = "";
        for (int i : outputs) {
            outputString += vocab.idxToToken.get(i);
        }
        return outputString;
    }

    /** Train a model. */
    public static void trainCh8(
            Object net,
            List<NDList> trainIter,
            Vocab vocab,
            int lr,
            int numEpochs,
            Device device,
            boolean useRandomIter,
            NDManager manager) {
        SoftmaxCrossEntropyLoss loss = new SoftmaxCrossEntropyLoss();
        Animator animator = new Animator();

        Functions.voidTwoFunction<Integer, NDManager> updater;
        if (net instanceof RNNModelScratch) {
            RNNModelScratch castedNet = (RNNModelScratch) net;
            updater =
                    (batchSize, subManager) ->
                            Training.sgd(castedNet.params, lr, batchSize, subManager);
        } else {
            // Already initialized net
            RNNModel castedNet = (RNNModel) net;
            Model model = Model.newInstance("model");
            model.setBlock(castedNet);

            Tracker lrt = Tracker.fixed(0.1f);
            Optimizer sgd = Optimizer.sgd().setLearningRateTracker(lrt).build();

            DefaultTrainingConfig config =
                    new DefaultTrainingConfig(loss)
                            .optOptimizer(sgd) // Optimizer (loss function)
                            .optInitializer(
                                    new NormalInitializer(0.01f),
                                    Parameter.Type.WEIGHT) // setting the initializer
                            .optDevices(Device.getDevices(1)) // setting the number of GPUs needed
                            .addEvaluator(new Accuracy()) // Model Accuracy
                            .addTrainingListeners(TrainingListener.Defaults.logging()); // Logging

            Trainer trainer = model.newTrainer(config);
            updater = (batchSize, subManager) -> trainer.step();
        }

        Function<String, String> predict =
                (prefix) -> predictCh8(prefix, 50, net, vocab, device, manager);
        // Train and predict
        double ppl = 0.0;
        double speed = 0.0;
        for (int epoch = 0; epoch < numEpochs; epoch++) {
            //            System.out.println("Epoch: " + epoch);
            Pair<Double, Double> pair =
                    trainEpochCh8(net, trainIter, loss, updater, device, useRandomIter, manager);
            ppl = pair.getKey();
            speed = pair.getValue();
            if ((epoch + 1) % 10 == 0) {
                animator.add(epoch + 1, (float) ppl, "ppl");
                animator.show();
            }
            //            System.out.format(
            //                    "perplexity: %.1f, %.1f tokens/sec on %s%n", ppl, speed,
            // device.toString());
        }
        System.out.format(
                "perplexity: %.1f, %.1f tokens/sec on %s%n", ppl, speed, device.toString());
        System.out.println(predict.apply("time traveller"));
        System.out.println(predict.apply("traveller"));
    }

    /** Train a model within one epoch. */
    public static Pair<Double, Double> trainEpochCh8(
            Object net,
            List<NDList> trainIter,
            Loss loss,
            Functions.voidTwoFunction<Integer, NDManager> updater,
            Device device,
            boolean useRandomIter,
            NDManager manager) {
        StopWatch watch = new StopWatch();
        watch.start();
        Accumulator metric = new Accumulator(2); // Sum of training loss, no. of tokens

        try (NDManager childManager = manager.newSubManager()) {
            NDArray state = null;
            for (NDList pair : trainIter) {
                NDArray X = pair.get(0).toDevice(Functions.tryGpu(0), true);
                X.attach(childManager);
                NDArray Y = pair.get(1).toDevice(Functions.tryGpu(0), true);
                Y.attach(childManager);
                if (state == null || useRandomIter) {
                    // Initialize `state` when either it is the first iteration or
                    // using random sampling
                    if (net instanceof RNNModelScratch) {
                        state =
                                ((RNNModelScratch) net)
                                        .beginState((int) X.getShape().getShape()[0], device);
                    }
                } else {
                    state.stopGradient();
                }
                if (state != null) {
                    state.attach(childManager);
                }

                NDArray y = Y.transpose().reshape(new Shape(-1));
                X = X.toDevice(device, false);
                y = y.toDevice(device, false);
                try (GradientCollector gc = Engine.getInstance().newGradientCollector()) {
                    NDArray yHat;
                    if (net instanceof RNNModelScratch) {
                        Pair<NDArray, NDArray> pairResult = ((RNNModelScratch) net).call(X, state);
                        yHat = pairResult.getKey();
                        state = pairResult.getValue();
                    } else {
                        NDList pairResult;
                        if (state == null) {
                            // Begin state
                            pairResult =
                                    ((RNNModel) net)
                                            .forwardInternal(
                                                    new ParameterStore(manager, false),
                                                    new NDList(X),
                                                    true,
                                                    null);
                        } else {
                            pairResult =
                                    ((RNNModel) net)
                                            .forwardInternal(
                                                    new ParameterStore(manager, false),
                                                    new NDList(X, state),
                                                    true,
                                                    null);
                        }
                        yHat = pairResult.get(0);
                        state = pairResult.get(1);
                    }

                    NDArray l = loss.evaluate(new NDList(y), new NDList(yHat)).mean();
                    //                    System.out.println("Loss: " + l.getFloat());
                    gc.backward(l);
                    metric.add(new float[] {l.getFloat() * y.size(), y.size()});
                }
                gradClipping(net, 1, childManager);
                updater.apply(1, childManager); // Since the `mean` function has been invoked
            }
        }
        return new Pair(Math.exp(metric.get(0) / metric.get(1)), metric.get(1) / watch.stop());
    }

    /** Clip the gradient. */
    public static void gradClipping(Object net, int theta, NDManager manager) {
        double result = 0;
        NDList params;
        if (net instanceof RNNModelScratch) {
            params = ((RNNModelScratch) net).params;
        } else {
            params = new NDList();
            for (Pair<String, Parameter> pair : ((RNNModel) net).getParameters()) {
                params.add(pair.getValue().getArray());
            }
        }
        for (NDArray p : params) {
            NDArray gradient = p.getGradient().stopGradient();
            gradient.attach(manager);
            result += gradient.pow(2).sum().getFloat();
        }
        double norm = Math.sqrt(result);
        if (norm > theta) {
            for (NDArray param : params) {
                NDArray gradient = param.getGradient().stopGradient();
                param.getGradient().set(new NDIndex(":"), gradient.mul(theta / norm));
            }
        }
    }
}
