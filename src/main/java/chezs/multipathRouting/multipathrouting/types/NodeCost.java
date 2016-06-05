package chezs.multipathRouting.multipathrouting.types;

import org.projectfloodlight.openflow.util.HexString;

import org.projectfloodlight.openflow.types.DatapathId;

public class NodeCost implements Comparable<NodeCost> {
    private final String nodeStr;
    private final DatapathId node;
    private final double cost;

    public String getDpidStr() {
        return nodeStr;
    }
    public DatapathId getDpid() {
        return node;
    }
    public double getCost() {
        return cost;
    }

    public NodeCost(DatapathId node, double cost) {
        this.node = node;
        this.nodeStr = HexString.toHexString(node.getLong());
        this.cost = cost;
    }

    @Override
    public int compareTo(NodeCost o) {
        if (o.cost == this.cost) {
            return (int)(this.node.getLong() - o.node.getLong());
        }
        double res =this.cost - o.cost;
        if (res > 0) {
        	return 1;
        } else if (res < 0) {
        	return -1;
        }
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        NodeCost other = (NodeCost) obj;
        if (node == null) {
            if (other.node != null)
                return false;
        } else if (!node.equals(other.node))
            return false;
        return true;
    }

    @Override
    public int hashCode() {
        assert false : "hashCode not designed";
        return 42;
    }

}

