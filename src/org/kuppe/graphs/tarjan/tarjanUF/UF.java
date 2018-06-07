package tarjanUF;

import java.util.ArrayList;
import java.util.List;
import javafx.util.Pair;

import tarjanUF.UFNode.ListStatus;
import tarjanUF.UFNode.UFStatus;

public class UF {

    private List<UFNode> list;

    public enum ClaimStatus {
        /*
         * claimSuccess: not dead and not yet visited its SCC.
         * claimFound: not dead and visited its SCC before.
         * claimDead: dead => SCC was found.
         */
        claimSuccess, claimFound, claimDead;
    };

    public enum PickStatus {
        // Used in locking of cyclic list.
        pickSuccess, pickDead;
    };

    public UF(int n) {
        this.list = new ArrayList<UFNode>(n);
    }

    /********* Union find Operations ****************/

    public int find(int nodeId) {
        UFNode node = list.get(nodeId);
        // @require: Atomicity
        int parent = node.parent;
        if (parent == 0) {
            return nodeId;
        }

        int root = this.find(parent);
        if (root != parent) {
            // @require: Atomicity
            node.parent = root;
        }
        return root;
    }

    public boolean sameSet(int a, int b) {
        if (a == b)
            return true;
        int rb = this.find(b);

        // Assuming a == find(a)
        if (a == rb) {
            return true;
        }

        if (rb < a) {
            // @require: Atomicity
            if (this.list.get(rb).parent == 0) {
                return false;
            }
        }

        if (this.list.get(a).parent == 0) {
            return false;
        }

        return this.sameSet(this.find(a), rb);
    }

    // Unite the sets of a and b. Also merges the cyclic lists together.
    public void unite(int a, int b) {
        int ra, rb, la, lb, na, nb;
        int Q, R;
        long workerQ, workerR;

        while (true) {
            ra = this.find(a);
            rb = this.find(b);

            // No need to unite.
            if (ra == rb) {
                return;
            }

            // Take highest index node as a root.
            if (ra < rb) {
                R = rb;
                Q = ra;
            } else {
                R = ra;
                Q = rb;
            }

            if (!this.lockUF(Q)) {
                continue;
            }
            break;
        }

        la = this.lockList(a);
        if (la == -1) {
            // @require: Check Again.
            this.unlockUF(Q);
            return;
        }

        lb = this.lockList(b);
        if (lb == -1) {
            this.unlockList(la);
            // @require: Check Again.
            this.unlockUF(Q);
            return;
        }

        // @require: Atomicity
        na = this.list.get(la).listNext;
        nb = this.list.get(lb).listNext;

        // Handle 1 element sets.
        if (na == 0) {
            na = la;
        }
        if (nb == 0) {
            nb = lb;
        }

        // Merge the two lists in O(1).
        // @require: Atomicity
        this.list.get(la).listNext = nb;
        this.list.get(lb).listNext = na;

        // @require: Atomicity
        this.list.get(Q).parent = R;

        // Merge the worker sets.
        workerQ = this.list.get(Q).workerSet;
        workerR = this.list.get(R).workerSet;

        if ((workerQ | workerR) != workerR) {
            // @require: Atomicity
            this.list.get(R).workerSet |= workerQ;
            // @require: Atomicity
            while (this.list.get(R).parent != 0) {
                R = this.find(R);
                this.list.get(R).workerSet |= workerQ;
            }
        }

        // Remove locks from everywhere.
        unlockList(la);
        unlockList(lb);
        unlockUF(Q);

        return;
    }

    /*************** Cyclic List Operations *****************/

    public boolean inList(int a) {
        // @require: Atomicity
        return (this.list.get(a).listStatus != ListStatus.listTomb);
    }

    public Pair<PickStatus, Integer> pickFromList(int state) {
        int a, b, c;
        int ret;
        ListStatus statusA, statusB;
        a = state;

        while(true) {
            // Loop until state of `a` is not locked.
            while (true) {
                // @require: Atomicity
                statusA = this.list.get(a).listStatus;

                if (statusA == ListStatus.listLive) {
                    return (new Pair<PickStatus, Integer>(PickStatus.pickSuccess, a));
                } else if (statusA == ListStatus.listTomb) {
                    break;
                }
            }

            // @require: Atomicity
            b = this.list.get(a).listNext;
            if (a == b || b == 0) {
                markDead(a);
                return (new Pair<PickStatus, Integer>(PickStatus.pickDead, -1));
            }

            // Loop until state of `b` is not locked.
            while (true) {
                // @require: Atomicity
                statusB = this.list.get(b).listStatus;

                if (statusB == ListStatus.listLive) {
                    return (new Pair<PickStatus, Integer>(PickStatus.pickSuccess, b));
                } else if (statusB == ListStatus.listTomb) {
                    break;
                }
            }

            // @require: Atomicity
            c = this.list.get(b).listNext;

            // @require: Atomicity
            if (this.list.get(a).listNext == b) {
                // Shorten the list.
                // @require: Atomicity
                this.list.get(a).listNext = c;
            }

            a = c;
        }
    }

    public boolean removeFromList(int a) {
        ListStatus statusA;

        while (true) {
            // @require: Atomicity
            statusA = this.list.get(a).listStatus;
            if (statusA == ListStatus.listLive) {
                // @require: CAS from listLive
                this.list.get(a).listStatus = ListStatus.listTomb;
                if (true) {
                    // @require: Globally visit the the state.
                    return true;
                }
            } else if (statusA == ListStatus.listTomb) {
                return false;
            }
        }
    }

    /*************** Obtain the colour of node *************/

    public ClaimStatus makeClaim(int nodeId, int worker) {
        long workerId = 1L << ((long) worker);
        int rootId = this.find(nodeId);
        UFNode root = this.list.get(rootId);

        // @require: Atomicity
        if (root.ufStatus == UFStatus.UFdead) {
            return ClaimStatus.claimDead;
        }

        if ((root.workerSet & workerId) != 0L) {
            return ClaimStatus.claimFound;
        }

        // @require: Atomicity
        long workerSet = root.workerSet;
        // @require: Atomicity
        root.workerSet |= workerId;
        // @require: Atomicity
        while (root.parent != 0) {
            root = this.list.get(this.find(rootId));
            // @require: Atomicity
            root.workerSet |= workerId;
        }
        return ClaimStatus.claimSuccess;
    }

    /************** Check whether(or Mark) node is(or as) dead **************/

    public boolean isDead(int a) {
        int ra = this.find(a);
        // @require: Atomicity
        return (this.list.get(ra).ufStatus == UFStatus.UFdead);
    }

    public boolean markDead(int a) {
        boolean result = false;
        int ra = this.find(a);
        // @require: Atomicity
        UFStatus stat = this.list.get(ra).ufStatus;

        while (stat != UFStatus.UFdead) {
            if (stat == UFStatus.UFlive) {
                // @require: CAS from UFlive
                this.list.get(ra).ufStatus = UFStatus.UFdead;
                result = true;
            }
            stat = this.list.get(ra).ufStatus;
        }
        return result;
    }

    /************** Locking Operations ***************/

    public boolean lockUF(int a) {
        // @require: Atomicity
        if (this.list.get(a).ufStatus == UFStatus.UFlive) {
            // @require: CAS from UFlive
            this.list.get(a).ufStatus = UFStatus.UFlock;
            if (true) {
                // @require: Atomicity
                if (this.list.get(a).parent == 0) {
                    return true;
                }

                // @require: Atomicity
                this.list.get(a).ufStatus = UFStatus.UFlive;
            }
        }
        return false;
    }

    public void unlockUF(int a) {
        // @require: Atomicity
        this.list.get(a).ufStatus = UFStatus.UFlive;
    }

    public int lockList(int a) {
        PickStatus picked;
        int la;

        while (true) {
            Pair<PickStatus, Integer> p = pickFromList(a);
            picked = p.getKey();
            la = p.getValue();
            if (picked == PickStatus.pickDead) {
                return -1;
            }
            // @require: CAS from listLive
            this.list.get(la).listStatus = ListStatus.listLock;
            if (true) {
                return la;
            }
        }
    }

    public void unlockList(int la) {
        // @require: Atomicity
        this.list.get(la).listStatus = ListStatus.listLive;
    }

}