package com.imyvm.finance.quote;

import java.util.*;

public final class SimulationFormula {
    public static final String DEFAULT = "CLAMP(TREND_BPS + VOLATILITY_BPS * RANDOM, -MAX_MOVE_BPS, MAX_MOVE_BPS)";
    public static final String STABLE = "CLAMP(TREND_BPS * 0.5 + VOLATILITY_BPS * RANDOM, -MAX_MOVE_BPS, MAX_MOVE_BPS)";
    public static final String VOLATILE = "CLAMP(TREND_BPS * 1.25 + VOLATILITY_BPS * RANDOM, -MAX_MOVE_BPS, MAX_MOVE_BPS)";
    private static final Map<String, SimulationFormula> CACHE = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, SimulationFormula> eldest) { return size() > 64; }
    });
    private final Node root;
    private SimulationFormula(Node root) { this.root = root; }
    public static SimulationFormula parse(String source) {
        if (source == null || source.isBlank() || source.length() > 512) throw new IllegalArgumentException("formula is empty or too long");
        Parser p = new Parser(source); Node root = p.expression(); p.skip(); if (!p.end()) throw p.error("unexpected token");
        List<Map<String, Double>> samples = List.of(
            Map.of("PREV_PRICE",10000d,"PREV_LOG_RETURN",0d,"DRIFT_BPS",10d,"TREND_BPS",10d,"VOLATILITY_BPS",20d,"MAX_MOVE_BPS",100d,"RANDOM",.25d,"ITERATION",1d,"HISTORY_COUNT",5d),
            Map.of("PREV_PRICE",1d,"PREV_LOG_RETURN",-20d,"DRIFT_BPS",-10d,"TREND_BPS",-10d,"VOLATILITY_BPS",0d,"MAX_MOVE_BPS",1d,"RANDOM",-.9d,"ITERATION",20d,"HISTORY_COUNT",5d),
            Map.of("PREV_PRICE",1000000d,"PREV_LOG_RETURN",80d,"DRIFT_BPS",100d,"TREND_BPS",100d,"VOLATILITY_BPS",250d,"MAX_MOVE_BPS",500d,"RANDOM",.9d,"ITERATION",3d,"HISTORY_COUNT",120d));
        for (Map<String, Double> sample : samples) if (!Double.isFinite(root.eval(sample))) throw new IllegalArgumentException("formula result is not finite");
        return new SimulationFormula(root);
    }
    public static SimulationFormula compile(String source) {
        SimulationFormula cached = CACHE.get(source);
        if (cached != null) return cached;
        SimulationFormula compiled = parse(source);
        CACHE.put(source, compiled);
        return compiled;
    }

    public double eval(Map<String, Double> values) { double value = root.eval(values); if (!Double.isFinite(value)) throw new IllegalArgumentException("formula result is not finite"); return value; }
    private interface Node { double eval(Map<String, Double> values); }
    private record Number(double value) implements Node { public double eval(Map<String,Double> v) { return value; } }
    private record Variable(String name) implements Node { public double eval(Map<String,Double> v) { return v.getOrDefault(name, Double.NaN); } }
    private record Unary(char op, Node node) implements Node { public double eval(Map<String,Double> v) { double x=node.eval(v); return op=='-'?-x:x; } }
    private record Binary(char op, Node left, Node right) implements Node { public double eval(Map<String,Double> v) { double a=left.eval(v),b=right.eval(v); return switch(op){case '+':yield a+b;case '-':yield a-b;case '*':yield a*b;case '/':yield b==0?Double.NaN:a/b;default:yield Double.NaN;}; } }
    private record Call(String name, List<Node> args) implements Node {
        public double eval(Map<String,Double> v) { double[] a=args.stream().mapToDouble(n->n.eval(v)).toArray(); return switch(name) {
            case "LN" -> a.length==1&&a[0]>0?Math.log(a[0]):Double.NaN; case "LOG10" -> a.length==1&&a[0]>0?Math.log10(a[0]):Double.NaN;
            case "LOG2" -> a.length==1&&a[0]>0?Math.log(a[0])/Math.log(2):Double.NaN; case "LOGN" -> a.length==2&&a[0]>0&&a[1]>0&&a[1]!=1?Math.log(a[0])/Math.log(a[1]):Double.NaN;
            case "EXP" -> a.length==1?Math.exp(a[0]):Double.NaN; case "POW" -> a.length==2?Math.pow(a[0],a[1]):Double.NaN; case "SQRT" -> a.length==1&&a[0]>=0?Math.sqrt(a[0]):Double.NaN;
            case "ABS" -> a.length==1?Math.abs(a[0]):Double.NaN; case "SIGN" -> a.length==1?Math.signum(a[0]):Double.NaN; case "MIN" -> a.length>0?Arrays.stream(a).min().orElse(Double.NaN):Double.NaN;
            case "MAX" -> a.length>0?Arrays.stream(a).max().orElse(Double.NaN):Double.NaN; case "CLAMP" -> a.length==3?Math.max(a[1],Math.min(a[0],a[2])):Double.NaN;
            case "FLOOR" -> a.length==1?Math.floor(a[0]):Double.NaN; case "CEIL" -> a.length==1?Math.ceil(a[0]):Double.NaN; case "ROUND" -> a.length==1?Math.rint(a[0]):Double.NaN; default -> Double.NaN; }; }
    }
    private static final Set<String> VARIABLES=Set.of("PREV_PRICE","PREV_LOG_RETURN","DRIFT_BPS","TREND_BPS","VOLATILITY_BPS","MAX_MOVE_BPS","RANDOM","ITERATION","HISTORY_COUNT");
    private static final Set<String> FUNCTIONS=Set.of("LN","LOG10","LOG2","LOGN","EXP","POW","SQRT","ABS","SIGN","MIN","MAX","CLAMP","FLOOR","CEIL","ROUND");
    private static final class Parser { final String s; int i; int nodes; Parser(String s){this.s=s;} void skip(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;} boolean end(){return i>=s.length();} boolean take(char c){if(i<s.length()&&s.charAt(i)==c){i++;return true;}return false;} void need(char c){skip();if(!take(c))throw error("expected "+c);} IllegalArgumentException error(String m){return new IllegalArgumentException(m+" at "+i);}
        Node expression(){Node n=term();for(;;){skip();if(take('+'))n=new Binary('+',n,term());else if(take('-'))n=new Binary('-',n,term());else return n;}}
        Node term(){Node n=factor();for(;;){skip();if(take('*'))n=new Binary('*',n,factor());else if(take('/'))n=new Binary('/',n,factor());else return n;}}
        Node factor(){if (++nodes > 256) throw error("formula is too complex"); skip();if(take('+'))return new Unary('+',factor());if(take('-'))return new Unary('-',factor());if(take('(')){Node n=expression();need(')');return n;}if(i<s.length()&&(Character.isDigit(s.charAt(i))||s.charAt(i)=='.'))return number();String name=identifier();skip();if(take('(')){List<Node>a=new ArrayList<>();skip();if(!take(')')){do{a.add(expression());skip();}while(take(','));need(')');}if(!FUNCTIONS.contains(name))throw error("unknown function "+name);return new Call(name,a);}if(!VARIABLES.contains(name))throw error("unknown variable "+name);return new Variable(name);}
        Node number(){int start=i;while(i<s.length()&&(Character.isDigit(s.charAt(i))||s.charAt(i)=='.'||s.charAt(i)=='e'||s.charAt(i)=='E'||((s.charAt(i)=='+'||s.charAt(i)=='-')&&i>start&&(s.charAt(i-1)=='e'||s.charAt(i-1)=='E'))))i++;try{return new Number(Double.parseDouble(s.substring(start,i)));}catch(Exception e){throw error("invalid number");}}
        String identifier(){int start=i;while(i<s.length()&&(Character.isLetterOrDigit(s.charAt(i))||s.charAt(i)=='_'))i++;if(start==i)throw error("expected value");return s.substring(start,i).toUpperCase(Locale.ROOT);}
    }
}
