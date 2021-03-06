----------------------------- MODULE ParTarjan -----------------------------
(***************************************************************************)
(* This is the algorithm of Figure 1 of the paper "Concurrent Depth-First  *)
(* Search Algorithms" by Gavin Lowe.                                       *)
(***************************************************************************)
EXTENDS Integers, Sequences, TLC

(***************************************************************************)
(* We define min(a, b) to be the minimum of the numbers `a' and `b'.       *)
(***************************************************************************)
min(a, b) == IF a =< b THEN a ELSE b

(***************************************************************************)
(* If seq is a sequence of length at least n, then first(n, seq) is the    *)
(* set consisting of the first n elements of seq, and removeFirst(n, seq)  *)
(* is the sequence obtained from seq by removing its first n elements.     *)
(*                                                                         *)
(* Note: TeX names like \in and \cap are used for some mathematical        *)
(* symbols.                                                                *)
(***************************************************************************)
first(n, seq) == {seq[i] : i \in 1..n}
removeFirst(n, seq) == [i \in 1..(Len(seq)-n) |-> seq[i+n]]

(***************************************************************************)
(* We declare the unspecified constants Nodes and Edges, which represent   *)
(* the sets of nodes and edges of the graph, and add the assumption that   *)
(* an edge is an ordered pair of nodes.                                    *)
(***************************************************************************)
CONSTANT Nodes, Edges
ASSUME Edges \subseteq Nodes \X Nodes

(***************************************************************************)
(* EdgesOf(node) is the set of edges in Edges that start at `node'.        *)
(***************************************************************************)
EdgesOf(node) == {e \in Edges : node = e[1]}
-----------------------------------------------------------------------------
(***************************************************************************)
(* To be able to check that the algorithm is producing the right answer,   *)
(* we define MCC to be the right answer--that is, the set of maximal       *)
(* connected components of the graph.                                      *)
(*                                                                         *)
(* We start by defining reachable so that Reachable(n, m) is true iff node *)
(* m is reachable from node n in the graph.  It's defined in terms of the  *)
(* following:                                                              *)
(*                                                                         *)
(*    NbrsNotIn(x, S) is the set of neighbors of x that are not in         *)
(*       the set S of nodes                                                *)
(*                                                                         *)
(*    RF(S) is defined to be true iff m is reachable from some node in S.  *)
(*       It is defined recursively, so it has to be declared RECURSIVE     *)
(*       before its definition.  (Every symbol that appears in an          *)
(*       expression has to be either a TLA+ primitive or else              *)
(*       explicitly declared or defined.)                                  *)
(*                                                                         *)
(* Reachable(n, m) is then defined to be true iff n = m or RF({n}) is      *)
(* true.                                                                   *)
(***************************************************************************)
Reachable(n, m) ==
  LET NbrsNotIn(x, S) == {y \in Nodes \ S : <<x, y>> \in Edges}
      RECURSIVE RF(_)
      RF(S) == LET Nxt == UNION { NbrsNotIn(x, S) : x \in S }
               IN  IF m \in S THEN TRUE
                              ELSE IF Nxt = {} THEN FALSE
                                               ELSE RF(S \cup Nxt)
  IN  (n = m) \/ RF({n})

(***************************************************************************)
(* MCC is defined to be the set of maximal strongly connected components.  *)
(* It is defined by recursively M so that if Partial is a set of maximal   *)
(* SCCs of the graph and Rest is the set of nodes not in any of those      *)
(* SCCs, then M(Partial, Rest) is a set of maximal SCCs containing one     *)
(* more element than Partial.  of maximal SCCs of the graph that contain   *)
(* all the nodes.                                                          *)
(*                                                                         *)
(* Note: The expression                                                    *)
(*                                                                         *)
(*           CHOOSE x \in S : P(x)                                         *)
(*                                                                         *)
(* equals an arbitrarily chosen x in the set S satisfying P(x), if one     *)
(* exists.  Otherwise, it's undefined and TLC will report an error if it   *)
(* tries to compute the expression's value.  Note that there's no          *)
(* nondeterminism.  For the same S and P, the expression always equals the *)
(* same value.                                                             *)
(***************************************************************************)
MCC ==
  LET RECURSIVE M(_, _)
      M(Partial, Rest) ==
        IF Rest = {} THEN Partial
                     ELSE LET x == CHOOSE x \in Rest : TRUE
                              CC == {y \in Rest : /\ Reachable(x, y)
                                                  /\ Reachable(y, x)}
                          IN M(Partial \cup {CC}, Rest \ CC)
  IN  M({}, Nodes)
----------------------------------------------------------------------------
CONSTANT Threads

(***************************************************************************
We now define the algorithm, which appears inside this comment.  It
represents the part of the state associated with a node by the
following variables:

  edgesUnexplored[node] - The set of unexplored edges from node,
                          initially EdgesOf(n).
  nodeStatus[node]      - The status of node - initially "unseen".
  nodeLowlink[node]     - node.lowlink, initially 0 [value irrelevant].
  nodeIndex[node]       - node.index, initially 0 [value irrelevant]

The stacks are represented by the values of the variables controlStack
and tarjanStack, initially the empty sequence.  SCCset is the set of
maximal subsets currently constructed.

The step labeled `a' nondeterministically chooses the node startNode
from Nodes and execuutes addNode(startNode).  The construct

  with (x = exp) { body }

defines x to equal the value of exp in `body'.

An atomic step consists of execution from one label to the next.
Hence, the `while' statement in the code executes the loop test and the
entire body of the loop (if the `while' hasn't terminated) as a single
step.  The inner `do' loop in Lowe's algorithm could be represented in
PlusCal as a `while'.  However, a `while' has to have a label, so the
entire loop execution can't be an entire step.  So, instead that `do'
loop is implemented as three assignment statements in the body of the
statement

   with (newSCC = first(nodePos, tarjanStack)) { ... }

--algorithm SeqTarjan
  { variables index = 0 ,
              edgesUnexplored = [n \in Nodes |-> EdgesOf(n)] ,
              nodeStatus     = [n \in Nodes |-> "unseen"] ,
              nodeLowlink    = [n \in Nodes |-> 0] ,
              nodeIndex      = [n \in Nodes |-> 0] ,
              SCCset = { } ;

    define { unseenNodes == {n \in Nodes : nodeStatus[n] = "unseen"} }

    macro updateLowlink(node, val) { nodeLowlink[node] := min(nodeLowlink[node], val) }

    macro push(node, stack) { stack := <<node>> \o stack }

    macro pop(stack) { stack := Tail(stack) }

    macro addNode(node)
      { nodeIndex[node]   := index ;
        nodeLowlink[node] := index ;
        nodeStatus[node]  := "in-progress";
        index := index + 1;
        push(node, controlStack);
        push(node, tarjanStack)
      }

    fair process (T \in Threads)
      variables controlStack = << >> ,
                tarjanStack  = << >> ,
                newSCC = {} ,
                node = CHOOSE n \in Nodes : TRUE ,
                w = node ,
                child = node ;
    {  a: while (unseenNodes # {})
           { with (startNode \in {n \in Nodes : nodeStatus[n] = "unseen"})
               { addNode(startNode) } };
       b:    while (controlStack # << >>)
              { node := Head(controlStack) ;
                if (edgesUnexplored[node] # {}) {
                    with (e \in edgesUnexplored[node]) {
                      edgesUnexplored[node] := edgesUnexplored[node] \ {e} ;
                      child := e[2] } ;
                    if (nodeStatus[child] = "unseen") {addNode(child) }
                    else if (\E i \in 1..Len(tarjanStack) :
                                    tarjanStack[i] = child) {
                              updateLowlink(node, nodeIndex[child]) }
                    else { e : skip (*await nodeStatus[child] = "complete"*) }
                 }

                else {
                  pop(controlStack) ;
                  if (controlStack # << >>)
                    { updateLowlink(controlStack[1], nodeLowlink[node]) } ;
                  if (nodeLowlink[node] = nodeIndex[node]) {
                    c: w := Head(tarjanStack) ;
                       pop(tarjanStack) ;
                       newSCC := newSCC \cup {w} ;
                       nodeStatus[w] := "complete" ;
                       if (w # node) {goto c } ;
                    d: SCCset :=  SCCset \cup {newSCC}
                  }
                }
              }
           }
    }
  }
 ***************************************************************************)
\* BEGIN TRANSLATION
VARIABLES index, edgesUnexplored, nodeStatus, nodeLowlink, nodeIndex, SCCset,
          pc

(* define statement *)
unseenNodes == {n \in Nodes : nodeStatus[n] = "unseen"}

VARIABLES controlStack, tarjanStack, newSCC, node, w, child

vars == << index, edgesUnexplored, nodeStatus, nodeLowlink, nodeIndex, SCCset,
           pc, controlStack, tarjanStack, newSCC, node, w, child >>

ProcSet == (Threads)

Init == (* Global variables *)
        /\ index = 0
        /\ edgesUnexplored = [n \in Nodes |-> EdgesOf(n)]
        /\ nodeStatus = [n \in Nodes |-> "unseen"]
        /\ nodeLowlink = [n \in Nodes |-> 0]
        /\ nodeIndex = [n \in Nodes |-> 0]
        /\ SCCset = { }
        (* Process T *)
        /\ controlStack = [self \in Threads |-> << >>]
        /\ tarjanStack = [self \in Threads |-> << >>]
        /\ newSCC = [self \in Threads |-> {}]
        /\ node = [self \in Threads |-> CHOOSE n \in Nodes : TRUE]
        /\ w = [self \in Threads |-> node[self]]
        /\ child = [self \in Threads |-> node[self]]
        /\ pc = [self \in ProcSet |-> "a"]

a(self) == /\ pc[self] = "a"
           /\ IF unseenNodes # {}
                 THEN /\ \E startNode \in {n \in Nodes : nodeStatus[n] = "unseen"}:
                           /\ nodeIndex' = [nodeIndex EXCEPT ![startNode] = index]
                           /\ nodeLowlink' = [nodeLowlink EXCEPT ![startNode] = index]
                           /\ nodeStatus' = [nodeStatus EXCEPT ![startNode] = "in-progress"]
                           /\ index' = index + 1
                           /\ controlStack' = [controlStack EXCEPT ![self] = <<startNode>> \o controlStack[self]]
                           /\ tarjanStack' = [tarjanStack EXCEPT ![self] = <<startNode>> \o tarjanStack[self]]
                      /\ pc' = [pc EXCEPT ![self] = "a"]
                 ELSE /\ pc' = [pc EXCEPT ![self] = "b"]
                      /\ UNCHANGED << index, nodeStatus, nodeLowlink,
                                      nodeIndex, controlStack, tarjanStack >>
           /\ UNCHANGED << edgesUnexplored, SCCset, newSCC, node, w, child >>

b(self) == /\ pc[self] = "b"
           /\ IF controlStack[self] # << >>
                 THEN /\ node' = [node EXCEPT ![self] = Head(controlStack[self])]
                      /\ IF edgesUnexplored[node'[self]] # {}
                            THEN /\ \E e \in edgesUnexplored[node'[self]]:
                                      /\ edgesUnexplored' = [edgesUnexplored EXCEPT ![node'[self]] = edgesUnexplored[node'[self]] \ {e}]
                                      /\ child' = [child EXCEPT ![self] = e[2]]
                                 /\ IF nodeStatus[child'[self]] = "unseen"
                                       THEN /\ nodeIndex' = [nodeIndex EXCEPT ![child'[self]] = index]
                                            /\ nodeLowlink' = [nodeLowlink EXCEPT ![child'[self]] = index]
                                            /\ nodeStatus' = [nodeStatus EXCEPT ![child'[self]] = "in-progress"]
                                            /\ index' = index + 1
                                            /\ controlStack' = [controlStack EXCEPT ![self] = <<child'[self]>> \o controlStack[self]]
                                            /\ tarjanStack' = [tarjanStack EXCEPT ![self] = <<child'[self]>> \o tarjanStack[self]]
                                            /\ pc' = [pc EXCEPT ![self] = "b"]
                                       ELSE /\ IF \E i \in 1..Len(tarjanStack[self]) :
                                                         tarjanStack[self][i] = child'[self]
                                                  THEN /\ nodeLowlink' = [nodeLowlink EXCEPT ![node'[self]] = min(nodeLowlink[node'[self]], (nodeIndex[child'[self]]))]
                                                       /\ pc' = [pc EXCEPT ![self] = "b"]
                                                  ELSE /\ pc' = [pc EXCEPT ![self] = "e"]
                                                       /\ UNCHANGED nodeLowlink
                                            /\ UNCHANGED << index, nodeStatus,
                                                            nodeIndex,
                                                            controlStack,
                                                            tarjanStack >>
                            ELSE /\ controlStack' = [controlStack EXCEPT ![self] = Tail(controlStack[self])]
                                 /\ IF controlStack'[self] # << >>
                                       THEN /\ nodeLowlink' = [nodeLowlink EXCEPT ![(controlStack'[self][1])] = min(nodeLowlink[(controlStack'[self][1])], (nodeLowlink[node'[self]]))]
                                       ELSE /\ TRUE
                                            /\ UNCHANGED nodeLowlink
                                 /\ IF nodeLowlink'[node'[self]] = nodeIndex[node'[self]]
                                       THEN /\ pc' = [pc EXCEPT ![self] = "c"]
                                       ELSE /\ pc' = [pc EXCEPT ![self] = "b"]
                                 /\ UNCHANGED << index, edgesUnexplored,
                                                 nodeStatus, nodeIndex,
                                                 tarjanStack, child >>
                 ELSE /\ pc' = [pc EXCEPT ![self] = "Done"]
                      /\ UNCHANGED << index, edgesUnexplored, nodeStatus,
                                      nodeLowlink, nodeIndex, controlStack,
                                      tarjanStack, node, child >>
           /\ UNCHANGED << SCCset, newSCC, w >>

e(self) == /\ pc[self] = "e"
           /\ TRUE
           /\ pc' = [pc EXCEPT ![self] = "b"]
           /\ UNCHANGED << index, edgesUnexplored, nodeStatus, nodeLowlink,
                           nodeIndex, SCCset, controlStack, tarjanStack,
                           newSCC, node, w, child >>

c(self) == /\ pc[self] = "c"
           /\ w' = [w EXCEPT ![self] = Head(tarjanStack[self])]
           /\ tarjanStack' = [tarjanStack EXCEPT ![self] = Tail(tarjanStack[self])]
           /\ newSCC' = [newSCC EXCEPT ![self] = newSCC[self] \cup {w'[self]}]
           /\ nodeStatus' = [nodeStatus EXCEPT ![w'[self]] = "complete"]
           /\ IF w'[self] # node[self]
                 THEN /\ pc' = [pc EXCEPT ![self] = "c"]
                 ELSE /\ pc' = [pc EXCEPT ![self] = "d"]
           /\ UNCHANGED << index, edgesUnexplored, nodeLowlink, nodeIndex,
                           SCCset, controlStack, node, child >>

d(self) == /\ pc[self] = "d"
           /\ SCCset' = (SCCset \cup {newSCC[self]})
           /\ pc' = [pc EXCEPT ![self] = "b"]
           /\ UNCHANGED << index, edgesUnexplored, nodeStatus, nodeLowlink,
                           nodeIndex, controlStack, tarjanStack, newSCC, node,
                           w, child >>

T(self) == a(self) \/ b(self) \/ e(self) \/ c(self) \/ d(self)

Next == (\E self \in Threads: T(self))
           \/ (* Disjunct to prevent deadlock on termination *)
              ((\A self \in ProcSet: pc[self] = "Done") /\ UNCHANGED vars)

Spec == /\ Init /\ [][Next]_vars
        /\ \A self \in Threads : WF_vars(T(self))

Termination == <>(\A self \in ProcSet: pc[self] = "Done")

\* END TRANSLATION
-----------------------------------------------------------------------------
(***************************************************************************)
(* TypeOK asserts type correctness of a state.  It's a good idea to check  *)
(* that this assertion is an invariant.                                    *)
(***************************************************************************)
TypeOK ==
  /\ index \in Nat
  /\ edgesUnexplored \in [Nodes -> SUBSET Edges]
  /\ nodeStatus      \in  [Nodes -> {"unseen", "in-progress", "complete"}]
  /\ nodeLowlink     \in  [Nodes -> Nat]
  /\ nodeIndex       \in  [Nodes -> Nat]
  /\ controlStack \in [Threads -> Seq(Nodes)]
  /\ tarjanStack  \in [Threads -> Seq(Nodes)]
  /\ newSCC \in [Threads -> SUBSET Nodes]
  /\ w \in [Threads -> Nodes]
  /\ node \in [Threads -> Nodes]
  /\ child \in [Threads -> Nodes]
  /\ SCCset \subseteq SUBSET Nodes
\*  /\ pc \in [Threads -> {"a", "b", "c", "d", "e", "Done"}]

Terminated == \A self \in ProcSet: pc[self] = "Done"

Correct == Terminated => SCCset = MCC
=============================================================================
\* Modification History
\* Last modified Thu Sep 24 03:14:50 PDT 2015 by lamport
\* Created Wed Sep 23 05:30:36 PDT 2015 by lamport
