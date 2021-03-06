package com.vaadin.data.provider.hierarchical;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.vaadin.data.TreeData;
import com.vaadin.data.provider.HierarchyMapper;
import com.vaadin.data.provider.TreeDataProvider;
import com.vaadin.server.SerializablePredicate;
import com.vaadin.shared.Range;
import com.vaadin.shared.data.DataCommunicatorClientRpc;

import elemental.json.JsonArray;

public class HierarchyMapperWithDataTest {

    private static final int ROOT_COUNT = 5;
    private static final int PARENT_COUNT = 4;
    private static final int LEAF_COUNT = 2;

    private static TreeData<Node> data = new TreeData<>();
    private TreeDataProvider<Node> provider;
    private HierarchyMapper<Node, SerializablePredicate<Node>> mapper;
    private static List<Node> testData;
    private static List<Node> roots;
    private int mapSize = ROOT_COUNT;

    @BeforeClass
    public static void setupData() {
        testData = generateTestData();
        roots = testData.stream().filter(item -> item.getParent() == null)
                .collect(Collectors.toList());
        data.addItems(roots,
                parent -> testData.stream().filter(
                        item -> Objects.equals(item.getParent(), parent))
                        .collect(Collectors.toList()));
    }

    @Before
    public void setup() {
        provider = new TreeDataProvider<>(data);
        mapper = new HierarchyMapper<>(provider);
    }

    @Test
    public void expandRootNode() {
        Assert.assertEquals("Map size should be equal to root node count",
                ROOT_COUNT, mapper.getTreeSize());
        expand(testData.get(0));
        Assert.assertEquals("Should be root count + once parent count",
                ROOT_COUNT + PARENT_COUNT, mapper.getTreeSize());
        checkMapSize();
    }

    @Test
    public void expandAndCollapseLastRootNode() {
        Assert.assertEquals("Map size should be equal to root node count",
                ROOT_COUNT, mapper.getTreeSize());
        expand(roots.get(roots.size() - 1));
        Assert.assertEquals("Should be root count + once parent count",
                ROOT_COUNT + PARENT_COUNT, mapper.getTreeSize());
        checkMapSize();
        collapse(roots.get(roots.size() - 1));
        Assert.assertEquals("Map size should be equal to root node count again",
                ROOT_COUNT, mapper.getTreeSize());
        checkMapSize();
    }

    @Test
    public void expandHiddenNode() {
        Assert.assertEquals("Map size should be equal to root node count",
                ROOT_COUNT, mapper.getTreeSize());
        expand(testData.get(1));
        Assert.assertEquals(
                "Map size should not change when expanding a hidden node",
                ROOT_COUNT, mapper.getTreeSize());
        checkMapSize();
        expand(roots.get(0));
        Assert.assertEquals("Hidden node should now be expanded as well",
                ROOT_COUNT + PARENT_COUNT + LEAF_COUNT, mapper.getTreeSize());
        checkMapSize();
        collapse(roots.get(0));
        Assert.assertEquals("Map size should be equal to root node count",
                ROOT_COUNT, mapper.getTreeSize());
        checkMapSize();
    }

    @Test
    public void expandLeafNode() {
        Assert.assertEquals("Map size should be equal to root node count",
                ROOT_COUNT, mapper.getTreeSize());
        expand(testData.get(0));
        expand(testData.get(1));
        Assert.assertEquals("Root and parent node expanded",
                ROOT_COUNT + PARENT_COUNT + LEAF_COUNT, mapper.getTreeSize());
        checkMapSize();
        expand(testData.get(2));
        Assert.assertEquals("Expanding a leaf node should have no effect",
                ROOT_COUNT + PARENT_COUNT + LEAF_COUNT, mapper.getTreeSize());
        checkMapSize();
    }

    @Test
    public void findParentIndexOfLeaf() {
        expand(testData.get(0));
        Assert.assertEquals("Could not find the root node of a parent",
                Integer.valueOf(0), mapper.getParentIndex(testData.get(1)));

        expand(testData.get(1));
        Assert.assertEquals("Could not find the parent of a leaf",
                Integer.valueOf(1), mapper.getParentIndex(testData.get(2)));
    }

    @Test
    public void fetchRangeOfRows() {
        expand(testData.get(0));
        expand(testData.get(1));

        List<Node> expectedResult = testData.stream()
                .filter(n -> roots.contains(n)
                        || n.getParent().equals(testData.get(0))
                        || n.getParent().equals(testData.get(1)))
                .collect(Collectors.toList());

        // Range containing deepest level of expanded nodes without their
        // parents in addition to root nodes at the end.
        Range range = Range.between(3, mapper.getTreeSize());
        verifyFetchIsCorrect(expectedResult, range);

        // Only the expanded two nodes, nothing more.
        range = Range.between(0, 2);
        verifyFetchIsCorrect(expectedResult, range);

        // Fetch everything
        range = Range.between(0, mapper.getTreeSize());
        verifyFetchIsCorrect(expectedResult, range);
    }

    @Test
    public void fetchRangeOfRowsWithSorting() {
        // Expand before sort
        expand(testData.get(0));
        expand(testData.get(1));

        // Construct a sorted version of test data with correct filters
        List<List<Node>> levels = new ArrayList<>();
        Comparator<Node> comparator = Comparator.comparing(Node::getNumber)
                .reversed();
        levels.add(testData.stream().filter(n -> n.getParent() == null)
                .sorted(comparator).collect(Collectors.toList()));
        levels.add(
                testData.stream().filter(n -> n.getParent() == testData.get(0))
                        .sorted(comparator).collect(Collectors.toList()));
        levels.add(
                testData.stream().filter(n -> n.getParent() == testData.get(1))
                        .sorted(comparator).collect(Collectors.toList()));

        List<Node> expectedResult = levels.get(0).stream().flatMap(root -> {
            Stream<Node> nextLevel = levels.get(1).stream()
                    .filter(n -> n.getParent() == root)
                    .flatMap(node -> Stream.concat(Stream.of(node),
                            levels.get(2).stream()
                                    .filter(n -> n.getParent() == node)));
            return Stream.concat(Stream.of(root), nextLevel);
        }).collect(Collectors.toList());

        // Apply sorting
        mapper.setInMemorySorting(comparator::compare);

        // Range containing deepest level of expanded nodes without their
        // parents in addition to root nodes at the end.
        Range range = Range.between(8, mapper.getTreeSize());
        verifyFetchIsCorrect(expectedResult, range);

        // Only the root nodes, nothing more.
        range = Range.between(0, ROOT_COUNT);
        verifyFetchIsCorrect(expectedResult, range);

        // Fetch everything
        range = Range.between(0, mapper.getTreeSize());
        verifyFetchIsCorrect(expectedResult, range);
    }

    @Test
    public void fetchWithFilter() {
        expand(testData.get(0));
        Node expandedNode = testData.get(2 + LEAF_COUNT); // Expand second node
        expand(expandedNode);

        SerializablePredicate<Node> filter = n -> n.getNumber() % 2 == 0;
        List<Node> expectedResult = testData.stream().filter(filter)
                .filter(n -> roots.contains(n)
                        || n.getParent().equals(testData.get(0))
                        || n.getParent().equals(expandedNode))
                .collect(Collectors.toList());

        mapper.setFilter(filter);

        // Fetch everything
        Range range = Range.between(0, mapper.getTreeSize());
        verifyFetchIsCorrect(expectedResult, range);
    }

    private void expand(Node node) {
        insertRows(mapper.doExpand(node, mapper.getIndexOf(node)));
    }

    private void collapse(Node node) {
        removeRows(mapper.doCollapse(node, mapper.getIndexOf(node)));
    }

    private void verifyFetchIsCorrect(List<Node> expectedResult, Range range) {
        List<Node> collect = mapper.fetchItems(range)
                .collect(Collectors.toList());
        for (int i = 0; i < range.length(); ++i) {
            Assert.assertEquals("Unexpected fetch results.",
                    expectedResult.get(i + range.getStart()), collect.get(i));
        }
    }

    private static List<Node> generateTestData() {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < ROOT_COUNT; ++i) {
            Node root = new Node();
            nodes.add(root);
            for (int j = 0; j < PARENT_COUNT; ++j) {
                Node parent = new Node(root);
                nodes.add(parent);
                for (int k = 0; k < LEAF_COUNT; ++k) {
                    nodes.add(new Node(parent));
                }
            }
        }
        return nodes;
    }

    private void checkMapSize() {
        Assert.assertEquals("Map size not properly updated",
                mapper.getTreeSize(), mapSize);
    }

    public void removeRows(Range range) {
        Assert.assertTrue("Index not in range",
                0 <= range.getStart() && range.getStart() < mapSize);
        Assert.assertTrue("Removing more items than in map",
                range.getEnd() <= mapSize);
        mapSize -= range.length();
    }

    public void insertRows(Range range) {
        Assert.assertTrue("Index not in range",
                0 <= range.getStart() && range.getStart() <= mapSize);
        mapSize += range.length();
    }
}
