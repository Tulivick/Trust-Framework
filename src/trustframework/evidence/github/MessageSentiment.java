/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package trustframework.evidence.github;

import com.alchemyapi.api.AlchemyAPI;
import java.io.IOException;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import org.eclipse.egit.github.core.Comment;
import org.eclipse.egit.github.core.PullRequest;
import org.eclipse.egit.github.core.User;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import trustframework.data.github.GitComment;
import trustframework.data.github.ProjectData;
import trustframework.evidence.EvidenceAnalyser;
import trustframework.exceptions.github.InvalidProjectData;
import trustframework.graph.TFEdge;
import trustframework.graph.TFGraph;
import uk.ac.wlv.sentistrength.SentiStrength;

/**
 *
 * @author guilherme
 */
public class MessageSentiment implements EvidenceAnalyser {

    private double weigth;
    private static final String EVIDENCE_LABEL = "Sentiment";
    private final SentiStrength sentiStrength;
    private final AlchemyAPI alchemyAPI;

    public MessageSentiment(double weigth) {
        this.weigth = weigth;
        sentiStrength = new SentiStrength();
        String initializationString[] = {"sentidata", "E:\\SentStrength_Data_Sept2011\\", "sentenceCombineTot", "paragraphCombineTot", "trinary"};
        sentiStrength.initialise(initializationString);
        alchemyAPI = AlchemyAPI.GetInstanceFromString("63fc826bafdd6c71851ccafe25a6a2b499d280c7");
    }

    @Override
    public String getEvidenceLabel() {
        return EVIDENCE_LABEL;
    }

    @Override
    public void analyseEvidence(Object projectData, TFGraph relationsGraph) throws InvalidProjectData {
        if (projectData instanceof ProjectData) {
            ProjectData pData = (ProjectData) projectData;
            generateParcialEdges(pData, relationsGraph);
            generateFinalEdges(pData, relationsGraph);
        } else {
            throw new InvalidProjectData("projectData is not an instance of " + ProjectData.class.getName());
        }
    }

    private void generateParcialEdges(ProjectData pData, TFGraph relationsGraph) {
        generateParcialEdges(pData, relationsGraph, null);
    }

    private void generateParcialEdges(ProjectData pData, TFGraph relationsGraph, Date lastAnalysis) {
        List<String> validUsers = pData.getInvolvedUsers().stream().map(User::getLogin).collect(Collectors.toList());
        pData.getAllComments().forEach((prId, prComments) -> {
            PullRequest pr = pData.getPullRequest(prId);
            if (lastAnalysis == null || pr.getUpdatedAt().after(lastAnalysis)) {
                prComments.stream().filter(c -> lastAnalysis == null || c.getUpdatedAt().after(lastAnalysis)).forEach(comment -> {
                    Set<String> targets = getTargets(comment);
                    targets.add(pr.getUser().getLogin());
                    String source = comment.getCreator().getLogin();
                    targets.stream().filter(target -> (!target.equals(source) && validUsers.contains(target))).forEach(target -> {
                        relationsGraph.addEdge(source, target, "P" + EVIDENCE_LABEL + ":" + pr.getNumber() + ":" + comment.getId(), getSentiment(comment), comment.getUpdatedAt());
                    });
                });
            }
        });
    }

    private void generateFinalEdges(ProjectData pData, TFGraph relationsGraph) {
        pData.getInvolvedUsers().forEach(user -> {
            Map<String, List<TFEdge>> collect = relationsGraph.outEdges(user.getLogin(), "P" + EVIDENCE_LABEL).stream().collect(Collectors.groupingBy(TFEdge::getTarget));
            collect.forEach((target, edges) -> {
                double proportion = edges.stream().mapToDouble(TFEdge::getWeight).average().orElse(0);
                relationsGraph.addEdge(user.getLogin(), target, EVIDENCE_LABEL, proportion, null);
            });
        });
    }

    @Override
    public double getWeight() {
        return weigth;
    }

    @Override
    public void setWeight(double weight) {
        this.weigth = weight;
    }

    private Set<String> getTargets(GitComment comment) {
        Set<String> entities = new HashSet<>();
        try {
            Document doc = alchemyAPI.TextGetRankedNamedEntities(comment.getBody());
            NodeList entitiesTag = doc.getElementsByTagName("Entity");
            for (int i = 0; i < entitiesTag.getLength(); i++) {
                entities.add(entitiesTag.item(i).getChildNodes().item(3).getTextContent());
            }
        } catch (IllegalArgumentException | IOException | SAXException | ParserConfigurationException | XPathExpressionException ex) {
            //do nothing
        }
        Pattern pattern = Pattern.compile("\\B@\\w+");
        Matcher matcher = pattern.matcher(comment.getBody());
        while(matcher.find()){
            entities.add(matcher.group().substring(1));
        }
        return entities;
    }

    private double getSentiment(GitComment comment) {
        try {
            String sentiment = sentiStrength.computeSentimentScores(comment.getBody()).split(" ")[2];
            return sentiment.equals("1") ? 1.0 : 0.0;
        } catch (NullPointerException ex) {
            return 0.0;
        }
    }

    @Override
    public void updateEvidence(Object projectData, TFGraph relations, Date limit, Date lastAnalysis) throws InvalidProjectData {
        if (projectData instanceof ProjectData) {
            ProjectData pData = (ProjectData) projectData;
            removeOldEdges(relations, limit);
            generateParcialEdges(pData, relations, lastAnalysis);
            generateFinalEdges(pData, relations);
        } else {
            throw new InvalidProjectData("projectData is not an instance of " + ProjectData.class.getName());
        }
    }

    private void removeOldEdges(TFGraph relations, Date until) {
        relations.allEdges(EVIDENCE_LABEL).stream().forEach(e -> {
            relations.removeEdge(e);
        });
        relations.allEdges("P" + EVIDENCE_LABEL + ":").stream().filter(e -> e.getDate().before(until)).forEach(e -> {
            relations.removeEdge(e);
        });
    }

}
