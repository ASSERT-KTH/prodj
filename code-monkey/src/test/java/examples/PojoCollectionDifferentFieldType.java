package examples;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PojoCollectionDifferentFieldType {

  private final LinkedHashMap<String, String> map;
  private final LinkedHashSet<String> set;
  private final LinkedList<String> list;

  public PojoCollectionDifferentFieldType(Map<String, String> map, Set<String> set, List<String> list) {
    this.map = new LinkedHashMap<>(map);
    this.set = new LinkedHashSet<>(set);
    this.list = new LinkedList<>(list);
  }

}
