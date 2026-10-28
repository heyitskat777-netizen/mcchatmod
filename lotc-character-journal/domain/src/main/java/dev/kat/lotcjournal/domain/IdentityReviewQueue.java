package dev.kat.lotcjournal.domain;
import java.util.*;
/** Bounded literal-name candidate queue. No Cartesian product of unrelated characters. */
public final class IdentityReviewQueue {
    private IdentityReviewQueue() {}
    public static IdentityReviewBatch pending(IdentityReviewData data,int limit) {
        if(limit<1||limit>100)throw new IllegalArgumentException("Invalid identity review batch size");
        var known=new HashMap<String,CharacterIdentity>();for(var value:data.identities())if(known.put(value.id(),value)!=null)throw new IllegalArgumentException("Duplicate character");
        var parents=new HashMap<String,String>();for(var link:data.merges())if(link.active()) {
            if(!known.containsKey(link.fromIdentity())||!known.containsKey(link.toIdentity())||parents.put(link.fromIdentity(),link.toIdentity())!=null)throw new IllegalArgumentException("Incomplete or conflicting identity snapshot");
        }
        var roots=new HashMap<String,String>();for(String id:known.keySet())root(id,parents,roots);
        var denied=new HashSet<IdentityReviewPair>();for(var decision:data.separations())if(decision.active()) {
            String a=roots.get(decision.firstIdentity()),b=roots.get(decision.secondIdentity());
            if(a==null||b==null||a.equals(b))throw new IllegalArgumentException("Invalid separation snapshot");denied.add(pair(a,b));
        }
        var names=new TreeMap<String,SortedSet<String>>();
        for(var value:data.identities()) {var observed=new HashSet<>(value.aliases());observed.add(value.name());
            for(String name:observed)names.computeIfAbsent(CharacterNameMatcher.key(name.strip()),k->new TreeSet<>()).add(roots.get(value.id()));
        }
        var result=new LinkedHashSet<IdentityReviewPair>();var matchingNames=new HashMap<IdentityReviewPair,String>();var scannedGroups=new HashSet<List<String>>();
        outer:for(var entry:names.entrySet()) {var ids=new ArrayList<>(entry.getValue());if(ids.size()<2||!scannedGroups.add(ids))continue;
            for(int first=0;first<ids.size();first++)for(int second=first+1;second<ids.size();second++) {
                var candidate=pair(ids.get(first),ids.get(second));if(!denied.contains(candidate)&&result.add(candidate))matchingNames.put(candidate,entry.getKey());
                if(result.size()>limit)break outer;
            }
        }
        var pairs=new ArrayList<>(result);boolean more=pairs.size()>limit;if(more){matchingNames.remove(pairs.removeLast());}return new IdentityReviewBatch(pairs,more,matchingNames);
    }
    private static IdentityReviewPair pair(String a,String b){return a.compareTo(b)<0?new IdentityReviewPair(a,b):new IdentityReviewPair(b,a);}
    private static String root(String start,Map<String,String> parents,Map<String,String> roots) {
        if(roots.containsKey(start))return roots.get(start);var path=new LinkedHashSet<String>();String id=start;
        while(parents.containsKey(id)&&!roots.containsKey(id)){if(path.size()>=10000||!path.add(id))throw new IllegalArgumentException("Cyclic identity links");id=parents.get(id);}
        String root=roots.getOrDefault(id,id);roots.put(start,root);for(String member:path)roots.put(member,root);return root;
    }
}
