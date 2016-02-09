/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package trustframework;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.swing.JFrame;
import org.jgraph.JGraph;
import org.jgrapht.ext.JGraphModelAdapter;
import trustframework.data.DataExtractor;
import trustframework.data.github.GitHubExtractor;
import trustframework.evidence.github.Colaboration;
import trustframework.evidence.github.ConversationMimicry;
import trustframework.evidence.EvidenceAnalyser;
import trustframework.evidence.github.KnowledgeAcceptance;
import trustframework.evidence.github.MessageSentiment;
import trustframework.evidence.github.ProfileComunality;
import trustframework.evidence.github.TaskDelegation;
import trustframework.exceptions.TFConfigException;
import trustframework.graph.TFEdge;
import trustframework.graph.TFGraph;
import trustframework.graph.TFGraphFactory;
import trustframework.graph.jgrapht.LabeledWeightedEdge;
import trustframework.graph.jgrapht.TFGraphImp;
import trustframework.graph.jgrapht.TFGraphImpFactory;

/**
 *
 * @author guilherme
 */
public class TrustFramework {

    private DataExtractor dExtractor;
    private TFGraph relations;
    private TFGraph trust;
    private TFGraphFactory factory;
    private int timeInterval;
    private Date intervalBegin;
    private Date lastAnalysis;
    private final List<EvidenceAnalyser> evidences;
    private String owner;
    private String repository;
    private Object projectData;
    private Timer timer;

    public TrustFramework() {
        this.evidences = new ArrayList<>();
    }

    public void setdExtractor(DataExtractor dExtractor) {
        this.dExtractor = dExtractor;
    }

    public void setFactory(TFGraphFactory factory) {
        this.factory = factory;
    }

    public int getTimeInterval() {
        return timeInterval;
    }

    public void setTimeInterval(int timeInterval) {
        this.timeInterval = timeInterval;
        calculateIntervalBegin();
        //relations = null;
        //trust = null;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public void addEvidence(EvidenceAnalyser e) {
        evidences.add(e);
    }

    public void removeEvidence(String name) {
        Optional<EvidenceAnalyser> evidence = evidences.stream().filter(e -> e.getEvidenceLabel().equals(name)).findFirst();
        if (evidence.isPresent()) {
            evidences.remove(evidence.get());
            if(relations!=null)
                relations.allEdges(evidence.get().getEvidenceLabel()).removeIf(edge -> true);
        }
    }

    public Date getLastAnalysis() {
        return lastAnalysis;
    }

    public TFGraph getRelationsGraph() throws TFConfigException {
        List<String> check = checkConfiguration();
        if (check.size() > 0) {
            throw new TFConfigException(check);
        }
        if (relations != null) {
            return relations;
        }
        System.out.println("Inicio Data!");
        projectData = dExtractor.getProjectData(owner, repository, intervalBegin);
        lastAnalysis = new Date();
        System.out.println("Inicio Relations!");
        relations = dExtractor.generateRelationsGraph(projectData, factory.instantiateTFGraph());
        return relations;
    }

    private List<String> checkConfiguration() {
        List<String> missingConfig = new ArrayList<>();
        if (factory == null) {
            missingConfig.add("Factory");
        }
        if (dExtractor == null) {
            missingConfig.add("DataExtractor");
        }
        if (owner == null) {
            missingConfig.add("Owner");
        }
        if (repository == null) {
            missingConfig.add("Repository");
        }
        return missingConfig;
    }

    private void calculateIntervalBegin() {
        if (timeInterval > 0) {
            Calendar today = Calendar.getInstance();
            today.add(Calendar.DAY_OF_MONTH, -timeInterval);
            intervalBegin = today.getTime();
        } else {
            intervalBegin = null;
        }
    }

    private void analyseRelationsGraph() throws TFConfigException {
        relations = getRelationsGraph();
        System.out.println("Inicio analise!");
        evidences.forEach(e -> {
            try {
                e.analyseEvidence(projectData, relations);
            } catch (Exception ex) {
                Logger.getLogger(TrustFramework.class.getName()).log(Level.SEVERE, null, ex);
            }
        });
    }

    private void addTrustEdge(String source, String target) {
        double weights = 0;
        double trustValue = 0;
        for (EvidenceAnalyser e : evidences) {
            weights += e.getWeight();
            TFEdge evidenceEdge = relations.edgesBetween(source, target, e.getEvidenceLabel()).stream().findFirst().orElse(null);
            double aux = evidenceEdge != null ? evidenceEdge.getWeight() * e.getWeight() : 0;
//            System.out.print(e.getEvidenceLabel()+":"+aux+" ");
            trustValue += aux;
        }
//        System.out.println(trustValue+"--"+weights);
        trust.addEdge(source, target, "Trust", trustValue / weights, lastAnalysis);
    }

    private void generateTrustGraph() {
        trust = factory.instantiateTFGraph();
        relations.allEdges("PR:").stream().collect(Collectors.groupingBy(TFEdge::getSource, Collectors.mapping(TFEdge::getTarget, Collectors.toSet()))).forEach((source, targets) -> {
            trust.addNode(source);
            targets.forEach(target -> {
                trust.addNode(target);
                addTrustEdge(source, target);
                addTrustEdge(target, source);
            });
        });
    }

    public TFGraph getTrustGraph() throws TFConfigException {
        if (trust == null) {
            analyseRelationsGraph();
            System.out.println("Inicio trust!");
            generateTrustGraph();
        }
        return trust;
    }

    public void updateTrustGraph() {
        if (relations != null) {
            calculateIntervalBegin();
            Date newLastAnalysis = new Date();
            projectData = dExtractor.getProjectData(owner, repository, intervalBegin);
            dExtractor.updateRelationsGraph(relations, projectData, intervalBegin);
            evidences.forEach(e -> {
                try {
                    e.updateEvidence(projectData, relations, intervalBegin, lastAnalysis);
                } catch (Exception ex) {
                    Logger.getLogger(TrustFramework.class.getName()).log(Level.SEVERE, null, ex);
                }
            });
            lastAnalysis = newLastAnalysis;
            trust = null;
        } else {
            System.err.println("ERRO: Grafo de relacionamentos inexistente!!!");
        }
    }

    public double queryTrust(String from, String to) {
        Optional<TFEdge> trustEdge = trust.outEdges(from, "Trust").stream().filter(edge -> edge.getTarget().equals(to)).findFirst();
        return trustEdge.isPresent() ? trustEdge.get().getWeight() : 0;
    }

    public void start(int interval) throws TFConfigException {
        timer = new Timer();
        analyseRelationsGraph();
        timer.schedule(new TimerTask() {

            @Override
            public void run() {
                updateTrustGraph();
            }
        }, 0, interval * 24 * 60 * 60 * 1000);
    }

    public void cancel() {
        timer.cancel();
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws TFConfigException, FileNotFoundException, IOException {
        TrustFramework tf = new TrustFramework();
        tf.setFactory(new TFGraphImpFactory());
        tf.setdExtractor(new GitHubExtractor("tulivick", "*******"));
        tf.setTimeInterval(34);
        tf.setOwner("NYTimes");
        tf.setRepository("objective-c-style-guide");
        tf.addEvidence(new ConversationMimicry(1));
        tf.addEvidence(new Colaboration(1));
        tf.addEvidence(new KnowledgeAcceptance(1));
        tf.addEvidence(new TaskDelegation(1));
        tf.addEvidence(new ProfileComunality(1, "E:\\Mestrado\\TF.properties"));
        tf.addEvidence(new MessageSentiment(1));
        //((TFGraphImp) tf.getRelationsGraph()).printGraph(new PrintStream("E:\\Mestrado\\graphR2.txt"));
        //((TFGraphImp) tf.getTrustGraph()).printGraph(new PrintStream("E:\\Mestrado\\graphT2.txt"));
        //((TFGraphImp) tf.getRelationsGraph()).printGraph(new PrintStream("E:\\Mestrado\\graphRA2.txt"));
        //tf.setTimeInterval(20);
        //tf.updateTrustGraph();
        //((TFGraphImp) tf.getTrustGraph()).printGraph(new PrintStream("E:\\Mestrado\\graphTA2.txt"));
        //((TFGraphImp) tf.getRelationsGraph()).printGraph(new PrintStream("E:\\Mestrado\\graphRAA2.txt"));
        JFrame frame = new JFrame("Grafo de confiança");
        JGraph jGraph = new JGraph(new JGraphModelAdapter<>(((TFGraphImp) tf.getTrustGraph()).getGraph()));
        frame.getContentPane().add(jGraph);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 320);
        frame.setVisible(true);
    }

}
