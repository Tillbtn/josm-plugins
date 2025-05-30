// License: GPL. For details, see LICENSE file.
package render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.util.ArrayList;

import s57.S57map;
import s57.S57map.GeomIterator;
import s57.S57map.Pflag;
import s57.S57map.Snode;
import s57.S57val.UniHLU;
import symbols.Areas;
import symbols.Symbols;
import symbols.Symbols.Caption;
import symbols.Symbols.Delta;
import symbols.Symbols.Form;
import symbols.Symbols.Handle;
import symbols.Symbols.Instr;
import symbols.Symbols.LineStyle;
import symbols.Symbols.Scheme;
import symbols.Symbols.SubSymbol;
import symbols.Symbols.Symbol;

/**
 * @author Malcolm Herring
 */
public final class Renderer {
    private Renderer() {
        // Hide default constructor for utilities classes
    }

    public static final double[] symbolScale = {
            256.0, 128.0, 64.0, 32.0, 16.0, 8.0, 4.0, 2.0, 1.0, 0.61, 0.372, 0.227, 0.138, 0.0843, 0.0514, 0.0313, 0.0191, 0.0117, 0.007};

    public enum LabelStyle { NONE, RRCT, RECT, ELPS, CIRC, VCLR, PCLR, HCLR }

    static ChartContext context;
    static S57map map;
    static double sScale;
    static Graphics2D g2;
    static int zoom;

    public static void reRender(Graphics2D g, Rectangle rect, int z, double factor, S57map m, ChartContext c) {
        g2 = g;
        zoom = z;
        context = c;
        map = m;
        sScale = symbolScale[zoom] * factor;
        if (map != null) {
            if (context.clip()) {
                Point2D tl = context.getPoint(new Snode(map.bounds.maxlat, map.bounds.minlon));
                Point2D br = context.getPoint(new Snode(map.bounds.minlat, map.bounds.maxlon));
                g2.clip(new Rectangle2D.Double(tl.getX(), tl.getY(), (br.getX() - tl.getX()), (br.getY() - tl.getY())));
            }
            g2.setBackground(context.background(map));
            g2.clearRect(rect.x, rect.y, rect.width, rect.height);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
            g2.setStroke(new BasicStroke(0, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            do {} while (!Rules.rules());
        }
        if ((context.grid() > 0) && (map != null)) {
        	rose();
        	grid();
        }
    }

    public static void symbol(Symbol symbol) {
        Point2D point = context.getPoint(Rules.feature.geom.centre);
        Symbols.drawSymbol(g2, symbol, sScale, point.getX(), point.getY(), null, null);
    }

    public static void symbol(Symbol symbol, Scheme scheme) {
        Point2D point = context.getPoint(Rules.feature.geom.centre);
        Symbols.drawSymbol(g2, symbol, sScale, point.getX(), point.getY(), scheme, null);
    }

    public static void symbol(Symbol symbol, Delta delta) {
        Point2D point = context.getPoint(Rules.feature.geom.centre);
        Symbols.drawSymbol(g2, symbol, sScale, point.getX(), point.getY(), null, delta);
    }

    public static void symbol(Symbol symbol, double scale, Delta delta) {
        Point2D point = context.getPoint(Rules.feature.geom.centre);
        Symbols.drawSymbol(g2, symbol, (sScale * scale), point.getX(), point.getY(), null, delta);
    }

    public static void symbol(Symbol symbol, Scheme scheme, Delta delta) {
        Point2D point = context.getPoint(Rules.feature.geom.centre);
        Symbols.drawSymbol(g2, symbol, sScale, point.getX(), point.getY(), scheme, delta);
    }

    public static void symbol(Symbol symbol, double scale, Scheme scheme) {
        Point2D point = context.getPoint(Rules.feature.geom.centre);
        Symbols.drawSymbol(g2, symbol, (sScale * scale), point.getX(), point.getY(), scheme, null);
    }

    public static void colLetters(ArrayList<?> cols) {
        	String str = "";
        	for (int i = 0; (i < cols.size()) && (i < 4); i++) {
        		str = str.concat(Rules.colourLetters.get(cols.get(i)));
        	}
        	labelText(str, new Font("Arial", Font.PLAIN, 40), Color.black, new Delta(Handle.TC, AffineTransform.getTranslateInstance(0, 40)));
    }

    public static void cluster(ArrayList<Symbol> symbols) {
        Rectangle2D.Double bbox = null;
        if (symbols.size() > 4) {
            for (Instr instr : symbols.get(0)) {
                if (instr.type == Form.BBOX) {
                    bbox = (Rectangle2D.Double) instr.params;
                    break;
                }
            }
            if (bbox == null) return;
        }
        switch (symbols.size()) {
        case 1:
            symbol(symbols.get(0), new Delta(Handle.CC, new AffineTransform()));
            break;
        case 2:
            symbol(symbols.get(0), new Delta(Handle.RC, new AffineTransform()));
            symbol(symbols.get(1), new Delta(Handle.LC, new AffineTransform()));
            break;
        case 3:
            symbol(symbols.get(0), new Delta(Handle.BC, new AffineTransform()));
            symbol(symbols.get(1), new Delta(Handle.TR, new AffineTransform()));
            symbol(symbols.get(2), new Delta(Handle.TL, new AffineTransform()));
            break;
        case 4:
            symbol(symbols.get(0), new Delta(Handle.BR, new AffineTransform()));
            symbol(symbols.get(1), new Delta(Handle.BL, new AffineTransform()));
            symbol(symbols.get(2), new Delta(Handle.TR, new AffineTransform()));
            symbol(symbols.get(3), new Delta(Handle.TL, new AffineTransform()));
            break;
        case 5:
            symbol(symbols.get(0), new Delta(Handle.BR, new AffineTransform()));
            symbol(symbols.get(1), new Delta(Handle.BL, new AffineTransform()));
            symbol(symbols.get(2), new Delta(Handle.TR, AffineTransform.getTranslateInstance(-bbox.width/2, 0)));
            symbol(symbols.get(3), new Delta(Handle.TC, new AffineTransform()));
            symbol(symbols.get(4), new Delta(Handle.TL, AffineTransform.getTranslateInstance(bbox.width/2, 0)));
            break;
        case 6:
            symbol(symbols.get(0), new Delta(Handle.BR, AffineTransform.getTranslateInstance(-bbox.width/2, 0)));
            symbol(symbols.get(1), new Delta(Handle.BC, new AffineTransform()));
            symbol(symbols.get(2), new Delta(Handle.BL, AffineTransform.getTranslateInstance(bbox.width/2, 0)));
            symbol(symbols.get(3), new Delta(Handle.TR, AffineTransform.getTranslateInstance(-bbox.width/2, 0)));
            symbol(symbols.get(4), new Delta(Handle.TC, new AffineTransform()));
            symbol(symbols.get(5), new Delta(Handle.TL, AffineTransform.getTranslateInstance(bbox.width/2, 0)));
            break;
        case 7:
            symbol(symbols.get(0), new Delta(Handle.BC, AffineTransform.getTranslateInstance(0, -bbox.height/2)));
            symbol(symbols.get(1), new Delta(Handle.RC, AffineTransform.getTranslateInstance(-bbox.width/2, 0)));
            symbol(symbols.get(2), new Delta(Handle.CC, new AffineTransform()));
            symbol(symbols.get(3), new Delta(Handle.LC, AffineTransform.getTranslateInstance(bbox.width/2, 0)));
            symbol(symbols.get(4), new Delta(Handle.TR, AffineTransform.getTranslateInstance(-bbox.width/2, bbox.height/2)));
            symbol(symbols.get(5), new Delta(Handle.TC, AffineTransform.getTranslateInstance(0, bbox.height/2)));
            symbol(symbols.get(6), new Delta(Handle.TL, AffineTransform.getTranslateInstance(bbox.width/2, bbox.height/2)));
            break;
        case 8:
            symbol(symbols.get(0), new Delta(Handle.BR, AffineTransform.getTranslateInstance(0, -bbox.height/2)));
            symbol(symbols.get(1), new Delta(Handle.BL, AffineTransform.getTranslateInstance(0, -bbox.height/2)));
            symbol(symbols.get(2), new Delta(Handle.RC, AffineTransform.getTranslateInstance(-bbox.width/2, 0)));
            symbol(symbols.get(3), new Delta(Handle.CC, new AffineTransform()));
            symbol(symbols.get(4), new Delta(Handle.LC, AffineTransform.getTranslateInstance(bbox.width/2, 0)));
            symbol(symbols.get(5), new Delta(Handle.TR, AffineTransform.getTranslateInstance(-bbox.width/2, bbox.height/2)));
            symbol(symbols.get(6), new Delta(Handle.TC, AffineTransform.getTranslateInstance(0, bbox.height/2)));
            symbol(symbols.get(7), new Delta(Handle.TL, AffineTransform.getTranslateInstance(bbox.width/2, bbox.height/2)));
            break;
        case 9:
            symbol(symbols.get(0), new Delta(Handle.BR, AffineTransform.getTranslateInstance(-bbox.width/2, -bbox.height/2)));
            symbol(symbols.get(1), new Delta(Handle.BC, AffineTransform.getTranslateInstance(0, -bbox.height/2)));
            symbol(symbols.get(2), new Delta(Handle.BL, AffineTransform.getTranslateInstance(bbox.width/2, -bbox.height/2)));
            symbol(symbols.get(3), new Delta(Handle.RC, AffineTransform.getTranslateInstance(-bbox.width/2, 0)));
            symbol(symbols.get(4), new Delta(Handle.CC, new AffineTransform()));
            symbol(symbols.get(5), new Delta(Handle.LC, AffineTransform.getTranslateInstance(bbox.width/2, 0)));
            symbol(symbols.get(6), new Delta(Handle.TR, AffineTransform.getTranslateInstance(-bbox.width/2, bbox.height/2)));
            symbol(symbols.get(7), new Delta(Handle.TC, AffineTransform.getTranslateInstance(0, bbox.height/2)));
            symbol(symbols.get(8), new Delta(Handle.TL, AffineTransform.getTranslateInstance(bbox.width/2, bbox.height/2)));
            break;
        }
    }

    private static Rectangle2D.Double symbolSize(Symbol symbol) {
        Symbol ssymb = symbol;
        while (ssymb != null) {
            for (Instr item : symbol) {
                if (item.type == Form.BBOX) {
                    return (Rectangle2D.Double) item.params;
                }
                if (item.type == Form.SYMB) {
                    ssymb = ((SubSymbol) item.params).instr;
                    break;
                }
            }
            if (ssymb == symbol)
                break;
        }
        return null;
    }

    public static void lineSymbols(Symbol prisymb, double space, Symbol secsymb, Symbol tersymb, int ratio, Color col) {
        if ((Rules.feature.geom.prim == Pflag.NOSP) || (Rules.feature.geom.prim == Pflag.POINT))
            return;
        Rectangle2D.Double prect = symbolSize(prisymb);
        Rectangle2D.Double srect = symbolSize(secsymb);
        Rectangle2D.Double trect = symbolSize(tersymb);
        if (srect == null)
            ratio = 0;
        if (prect != null) {
            double psize = Math.abs(prect.getY()) * sScale;
            double ssize = (srect != null) ? Math.abs(srect.getY()) * sScale : 0;
            double tsize = (trect != null) ? Math.abs(srect.getY()) * sScale : 0;
            Point2D prev = new Point2D.Double();
            Point2D next = new Point2D.Double();
            Point2D curr = new Point2D.Double();
            Point2D succ = new Point2D.Double();
            boolean gap = true;
            boolean piv = false;
            double len = 0;
            double angle = 0;
            int stcount = ratio;
            boolean stflag = false;
            Symbol symbol = prisymb;
            GeomIterator git = map.new GeomIterator(Rules.feature.geom);
            while (git.hasComp()) {
                git.nextComp();
                boolean first = true;
                while (git.hasEdge()) {
                    git.nextEdge();
                    while (git.hasNode()) {
                        Snode node = git.next();
                        if (node == null) continue;
                        prev = next;
                        next = context.getPoint(node);
                        angle = Math.atan2(next.getY() - prev.getY(), next.getX() - prev.getX());
                        piv = true;
                        if (first) {
                            curr = succ = next;
                            gap = (space > 0);
                            stcount = ratio - 1;
                            symbol = prisymb;
                            len = gap ? psize * space * 0.5 : psize;
                            first = false;
                        } else {
                            while (curr.distance(next) >= len) {
                                if (piv) {
                                    double rem = len;
                                    double s = prev.distance(next);
                                    double p = curr.distance(prev);
                                    if ((s > 0) && (p > 0)) {
                                        double n = curr.distance(next);
                                        double theta = Math.acos((s * s + p * p - n * n) / 2 / s / p);
                                        double phi = Math.asin(p / len * Math.sin(theta));
                                        rem = len * Math.sin(Math.PI - theta - phi) / Math.sin(theta);
                                    }
                                    succ = new Point2D.Double(prev.getX() + (rem * Math.cos(angle)), prev.getY() + (rem * Math.sin(angle)));
                                    piv = false;
                                } else {
                                    succ = new Point2D.Double(curr.getX() + (len * Math.cos(angle)), curr.getY() + (len * Math.sin(angle)));
                                }
                                if (!gap) {
                                    Symbols.drawSymbol(g2, symbol, sScale, curr.getX(), curr.getY(), new Scheme(col),
                                            new Delta(Handle.BC, AffineTransform.getRotateInstance(
                                                    Math.atan2((succ.getY() - curr.getY()), (succ.getX() - curr.getX())) + Math.toRadians(90))));
                                }
                                if (space > 0)
                                    gap = !gap;
                                curr = succ;
                                len = gap ? (psize * space) : (--stcount == 0) ? (stflag ? tsize : ssize) : psize;
                                if (stcount == 0) {
                                    symbol = stflag ? tersymb : secsymb;
                                    if (trect != null)
                                        stflag = !stflag;
                                    stcount = ratio;
                                } else {
                                    symbol = prisymb;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public static void lineVector(LineStyle style) {
        Path2D.Double p = new Path2D.Double();
        p.setWindingRule(GeneralPath.WIND_EVEN_ODD);
        Point2D point;
        GeomIterator git = map.new GeomIterator(Rules.feature.geom);
        while (git.hasComp()) {
            git.nextComp();
            boolean first = true;
            while (git.hasEdge()) {
                git.nextEdge();
                point = context.getPoint(git.next());
                if (first) {
                    p.moveTo(point.getX(), point.getY());
                    first = false;
                } else {
                    p.lineTo(point.getX(), point.getY());
                }
                while (git.hasNode()) {
                    Snode node = git.next();
                    if (node == null) continue;
                    point = context.getPoint(node);
                    p.lineTo(point.getX(), point.getY());
                }
            }
        }
        if ((style.fill != null) && (Rules.feature.geom.prim == Pflag.AREA)) {
            g2.setPaint(style.fill);
            g2.fill(p);
        }
        if (style.line != null) {
            if (style.dash != null) {
                float[] dash = new float[style.dash.length];
                System.arraycopy(style.dash, 0, dash, 0, style.dash.length);
                for (int i = 0; i < style.dash.length; i++) {
                    dash[i] *= (float) sScale;
                }
                g2.setStroke(new BasicStroke((float) (style.width * sScale), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, dash, 0));
            } else {
                g2.setStroke(new BasicStroke((float) (style.width * sScale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            }
            g2.setPaint(style.line);
            g2.draw(p);
        }
    }
    
    public static void grid() {
        if ((context.grid() > 0) && (map != null)) {
            LineStyle style = new LineStyle(Color.black, (float)2.0);
            Point2D point = context.getPoint(new Snode(map.bounds.minlat, map.bounds.maxlon));
            double ratio = point.getX() / point.getY();
            double nspan = 60 * Math.toDegrees(map.bounds.maxlon - map.bounds.minlon) / (context.grid() * (ratio > 1.0 ? ratio : 1.0));
            double mult = 1.0;
            boolean ndiv = false;
            if (nspan < 1.0) {
                do {
                    nspan *= 10.0;
                    mult *= 10.0;
                } while (nspan < 1.0);
             } else if (nspan > 10.0){
                do {
                    nspan /= 10.0;
                    mult /= 10.0;
                } while (nspan > 10.0);
            }
            if (nspan < 2.0) nspan = 1.0;
            else if (nspan < 5.0) nspan = 2.0;
            else {
            	nspan = 5.0;
            	ndiv = true;
            }
            nspan = nspan / mult / 60.0;
            double left = Math.toDegrees(map.bounds.minlon) + 180.0;
            left = Math.ceil(left / nspan);
            left = Math.toRadians((left * nspan) - 180.0);
            double ndeg = Math.toRadians(nspan); 
            double tspan = 60 * Math.toDegrees(map.bounds.maxlat - map.bounds.minlat) / (context.grid() / (ratio < 1.0 ? ratio : 1.0));
            mult = 1.0;
            boolean tdiv = false;
            if (tspan < 1.0) {
                do {
                    tspan *= 10.0;
                    mult *= 10.0;
                } while (tspan < 1.0);
             } else if (tspan > 10.0){
                do {
                    tspan /= 10.0;
                    mult /= 10.0;
                } while (tspan > 10.0);
            }
            if (tspan < 2.0) tspan = 1.0;
            else if (tspan < 5.0) tspan = 2.0;
            else {
            	tspan = 5.0;
            	tdiv = true;
            }
            tspan = tspan / mult / 60.0;
            double bottom = Math.toDegrees(map.bounds.minlat) + 90.0;
            bottom = Math.ceil(bottom / tspan);
            bottom = Math.toRadians((bottom * tspan) - 90.0);
            double tdeg = Math.toRadians(tspan); 
            g2.setStroke(new BasicStroke((float) (style.width * sScale), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            Path2D.Double p = new Path2D.Double();
            for (double lon = left; lon < map.bounds.maxlon; lon += ndeg) {
                point = context.getPoint(new Snode(map.bounds.maxlat, lon));
                p.moveTo(point.getX(), point.getY());
                point = context.getPoint(new Snode(map.bounds.minlat, lon));
                p.lineTo(point.getX(), point.getY());
                double deg = Math.toDegrees(lon);
                String ew = (deg < -0.001) ? "W" : (deg > 0.001) ? "E" : "";
                deg = Math.abs(deg);
                String dstr = String.format("%03d°", (int)Math.floor(deg));
                double min = (deg - Math.floor(deg)) * 60.0;
                String mstr = String.format("%05.2f'%s", min, ew);
                Symbol label = new Symbol();
                if (point.getX() > 600.0) {
                    label.add(new Instr(Form.TEXT, new Caption(dstr, new Font("Arial", Font.PLAIN, 40), Color.black, new Delta(Handle.BR, AffineTransform.getTranslateInstance(-10, -40)))));
                    label.add(new Instr(Form.TEXT, new Caption(mstr, new Font("Arial", Font.PLAIN, 40), Color.black, new Delta(Handle.BL, AffineTransform.getTranslateInstance(20, 0)))));
                    Symbols.drawSymbol(g2, label, sScale, point.getX(), point.getY(), null, null);
                }
                for (int i = 1; i < 10; i++) {
                    int grad = (i % (ndiv ? 2 : 5)) == 0 ? 2 : 1;
                    point = context.getPoint(new Snode(map.bounds.maxlat, lon + (i * (ndeg / 10))));
                    p.moveTo(point.getX(), point.getY());
                    point = context.getPoint(new Snode(map.bounds.maxlat - (grad * (tdeg / 20)), lon + (i * (ndeg / 10))));
                    p.lineTo(point.getX(), point.getY());
                    point = context.getPoint(new Snode(map.bounds.minlat, lon + (i * (ndeg / 10))));
                    p.moveTo(point.getX(), point.getY());
                    point = context.getPoint(new Snode(map.bounds.minlat + (grad * (tdeg / 20)), lon + (i * (ndeg / 10))));
                    p.lineTo(point.getX(), point.getY());
                }
            }
            g2.setPaint(style.line);
            g2.draw(p);
            g2.setStroke(new BasicStroke((float) (style.width * sScale), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            p = new Path2D.Double();
            for (double lat = bottom; lat < map.bounds.maxlat; lat += tdeg) {
                point = context.getPoint(new Snode(lat, map.bounds.maxlon));
                p.moveTo(point.getX(), point.getY());
                point = context.getPoint(new Snode(lat, map.bounds.minlon));
                p.lineTo(point.getX(), point.getY());
                double deg = Math.toDegrees(lat);
                String ns = (deg < -0.001) ? "S" : (deg > 0.001) ? "N" : "";
                deg = Math.abs(deg);
                String dstr = String.format("%02d°%s", (int)Math.floor(deg), ns);
                double min = (deg - Math.floor(deg)) * 60.0;
                String mstr = String.format("%05.2f'", min);
                Symbol label = new Symbol();
                if (point.getY() < (context.getPoint(new Snode(map.bounds.minlat, map.bounds.minlon)).getY() - 200.0)) {
                	label.add(new Instr(Form.TEXT, new Caption(dstr, new Font("Arial", Font.PLAIN, 40), Color.black, new Delta(Handle.BL, AffineTransform.getTranslateInstance(40, -10)))));
                	label.add(new Instr(Form.TEXT, new Caption(mstr, new Font("Arial", Font.PLAIN, 40), Color.black, new Delta(Handle.BL, AffineTransform.getTranslateInstance(0, 50)))));
                	Symbols.drawSymbol(g2, label, sScale, point.getX(), point.getY(), null, null);
                }
                for (int i = 1; i < 10; i++) {
                    int grad = (i % (tdiv ? 2 : 5)) == 0 ? 2 : 1;
                    point = context.getPoint(new Snode(lat + (i * (tdeg / 10)), map.bounds.maxlon));
                    p.moveTo(point.getX(), point.getY());
                    point = context.getPoint(new Snode(lat + (i * (tdeg / 10)), map.bounds.maxlon - (grad * (ndeg / 20))));
                    p.lineTo(point.getX(), point.getY());
                    point = context.getPoint(new Snode(lat + (i * (tdeg / 10)), map.bounds.minlon));
                    p.moveTo(point.getX(), point.getY());
                    point = context.getPoint(new Snode(lat + (i * (tdeg / 10)), map.bounds.minlon + (grad * (ndeg / 20))));
                    p.lineTo(point.getX(), point.getY());
                }
            }
            g2.setPaint(style.line);
            g2.draw(p);
            Symbol legend = new Symbol();
            legend.add(new Instr(Form.BBOX, new Rectangle2D.Double(0, 0, 600, 250)));
            Path2D.Double path = new Path2D.Double(); path.moveTo(0, 0); path.lineTo(600, 0); path.lineTo(600, 250); path.lineTo(0, 250); path.closePath();
            legend.add(new Instr(Form.FILL, Color.white));
            legend.add(new Instr(Form.PGON, path));
            legend.add(new Instr(Form.TEXT, new Caption("Mercator Projection", new Font("Arial", Font.PLAIN, 50), Color.black, new Delta(Handle.BC, AffineTransform.getTranslateInstance(300, 60)))));
            legend.add(new Instr(Form.TEXT, new Caption("© OpenStreetMap contributors", new Font("Arial", Font.PLAIN, 30), Color.black, new Delta(Handle.BC, AffineTransform.getTranslateInstance(0, 40)))));
            legend.add(new Instr(Form.TEXT, new Caption("EMODnet Bathymetry Consortium (2018)", new Font("Arial", Font.PLAIN, 30), Color.black, new Delta(Handle.BC, AffineTransform.getTranslateInstance(0, 40)))));
            legend.add(new Instr(Form.TEXT, new Caption("EMODnet Digital Bathymetry (DTM)", new Font("Arial", Font.PLAIN, 30), Color.black, new Delta(Handle.BC, AffineTransform.getTranslateInstance(0, 40)))));
            legend.add(new Instr(Form.TEXT, new Caption("http://doi.org/10.12770/18ff0d48-b203-4a65-94a9-5fd8b0ec35f6", new Font("Arial", Font.PLAIN, 20), Color.black, new Delta(Handle.BC, AffineTransform.getTranslateInstance(0, 35)))));
            point = context.getPoint(new Snode(map.bounds.minlat, map.bounds.minlon));
            Symbols.drawSymbol(g2, legend, sScale, point.getX(), point.getY(), null, new Delta(Handle.BL, AffineTransform.getTranslateInstance(0, 0)));
        }
    }
    
    public static void rose() {
        LineStyle style = new LineStyle(Color.black, (float)2.0);
        Point2D point = context.getPoint(new Snode(Math.toRadians(53.91649), Math.toRadians(-0.16141)));
        g2.setPaint(Color.white);
        g2.fill(new Ellipse2D.Double(point.getX() - 30, point.getY() - 30, 60, 60));
        g2.setStroke(new BasicStroke((float) (style.width * sScale), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        Path2D.Double p = new Path2D.Double();
        p.moveTo(point.getX() - 30, point.getY());p.lineTo(point.getX() + 30, point.getY());
        p.moveTo(point.getX(), point.getY() - 30);p.lineTo(point.getX(), point.getY() + 30);
        for (int i = 0; i < 360; i++) {
        	double inner = ((i % 10) == 0) ? 0.92 : ((i % 5) == 0) ? 0.96 : 0.98;
        	double xouter = 1750 * Math.sin(Math.toRadians(i));
        	double youter = -1750 * Math.cos(Math.toRadians(i));
            p.moveTo(point.getX() + xouter, point.getY() + youter);
            p.lineTo(point.getX() + (inner * xouter), point.getY() + (inner * youter));
            if ((i % 10) == 0) {
            	Handle h = Handle.BC;
            	if ((i > 0) && (i <= 90)) h = Handle.BL;
            	else if (i == 90) h = Handle.LC;
            	else if ((i > 90) && (i < 180)) h = Handle.LC;
            	else if (i == 180) h = Handle.CC;
            	else if ((i > 180) && (i <= 270)) h = Handle.RC;
            	else if (i > 270) h = Handle.BR;
            	Symbol value = new Symbol();
            	value.add(new Instr(Form.BBOX, new Rectangle2D.Double(0, 0, 60, 30)));
                value.add(new Instr(Form.TEXT, new Caption(String.format("%03d", i), new Font("Arial", Font.PLAIN, 40), Color.black, new Delta(Handle.CC, AffineTransform.getTranslateInstance(30, 25)))));
                Symbols.drawSymbol(g2, value, sScale, point.getX(), point.getY(), null, new Delta(h, AffineTransform.getTranslateInstance(1.02*xouter/sScale, 1.02*youter/sScale)));
            }
        }
        g2.setPaint(style.line);
        g2.draw(p);
    }
    
    public static void lineCircle(LineStyle style, double radius, UniHLU units) {
        switch (units) {
        case HLU_FEET:
            radius /= 6076;
            break;
        case HLU_KMTR:
            radius /= 1.852;
            break;
        case HLU_HMTR:
            radius /= 18.52;
            break;
        case HLU_SMIL:
            radius /= 1.15078;
            break;
        case HLU_NMIL:
            break;
        default:
            radius /= 1852;
            break;
        }
        radius *= context.mile(Rules.feature);
        Symbol circle = new Symbol();
        if (style.fill != null) {
            circle.add(new Instr(Form.FILL, style.fill));
            circle.add(new Instr(Form.RSHP, new Ellipse2D.Double(-radius, -radius, radius*2, radius*2)));
        }
        circle.add(new Instr(Form.FILL, style.line));
        circle.add(new Instr(Form.STRK, new BasicStroke(style.width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, style.dash, 0)));
        circle.add(new Instr(Form.ELPS, new Ellipse2D.Double(-radius, -radius, radius*2, radius*2)));
        Point2D point = context.getPoint(Rules.feature.geom.centre);
        Symbols.drawSymbol(g2, circle, 1, point.getX(), point.getY(), null, null);
    }

    public static void fillPattern(BufferedImage image) {
        Path2D.Double p = new Path2D.Double();
        p.setWindingRule(GeneralPath.WIND_EVEN_ODD);
        Point2D point;
        switch (Rules.feature.geom.prim) {
        case POINT:
            point = context.getPoint(Rules.feature.geom.centre);
            g2.drawImage(image, new AffineTransformOp(AffineTransform.getScaleInstance(sScale, sScale), AffineTransformOp.TYPE_NEAREST_NEIGHBOR),
                    (int) (point.getX() - (sScale * image.getWidth() / 2)), (int) (point.getY() - (sScale * image.getHeight() / 2)));
            break;
        case AREA:
            GeomIterator git = map.new GeomIterator(Rules.feature.geom);
            while (git.hasComp()) {
                git.nextComp();
                boolean newComp = true;
                while (git.hasEdge()) {
                    git.nextEdge();
                    point = context.getPoint(git.next());
                    if (newComp) {
                        p.moveTo(point.getX(), point.getY());
                        newComp = false;
                    } else {
                        p.lineTo(point.getX(), point.getY());
                    }
                    while (git.hasNode()) {
                        Snode node = git.next();
                        if (node == null) continue;
                        point = context.getPoint(node);
                        p.lineTo(point.getX(), point.getY());
                    }
                }
            }
            g2.setPaint(new TexturePaint(image, new Rectangle(0, 0, (1 + (int) (150 * sScale)), (1 + (int) (150 * sScale)))));
            g2.fill(p);
            break;
        default:
            break;
        }
    }

    public static void labelText(String str, Font font, Color tc) {
        labelText(str, font, tc, LabelStyle.NONE, null, null, null);
    }

    public static void labelText(String str, Font font, Color tc, Delta delta) {
        labelText(str, font, tc, LabelStyle.NONE, null, null, delta);
    }

    public static void labelText(String str, Font font, Color tc, LabelStyle style, Color fg) {
        labelText(str, font, tc, style, fg, null, null);
    }

    public static void labelText(String str, Font font, Color tc, LabelStyle style, Color fg, Color bg) {
        labelText(str, font, tc, style, fg, bg, null);
    }

    public static void labelText(String str, Font font, Color tc, LabelStyle style, Color fg, Delta delta) {
        labelText(str, font, tc, style, fg, null, delta);
    }

    public static void labelText(String str, Font font, Color tc, LabelStyle style, Color fg, Color bg, Delta delta) {
        if (delta == null) delta = new Delta(Handle.CC);
        if (bg == null) bg = new Color(0x00000000, true);
        if (str == null || str.isEmpty()) str = " ";
        FontRenderContext frc = g2.getFontRenderContext();
        GlyphVector gv = font.deriveFont((float) font.getSize()).createGlyphVector(frc, str.equals(" ") ? "M" : str);
        Rectangle2D bounds = gv.getVisualBounds();
        double width = bounds.getWidth();
        double height = bounds.getHeight();
        Symbol label = new Symbol();
        double lx, ly, tx, ty;
        switch (style) {
        case RRCT:
            width += height * 1.0;
            height *= 1.5;
            if (width < height) width = height;
            lx = -width / 2;
            ly = -height / 2;
            tx = lx + (height * 0.34);
            ty = ly + (height * 0.17);
            label.add(new Instr(Form.BBOX, new Rectangle2D.Double(lx, ly, width, height)));
            label.add(new Instr(Form.FILL, bg));
            label.add(new Instr(Form.RSHP, new RoundRectangle2D.Double(lx, ly, width, height, height, height)));
            label.add(new Instr(Form.FILL, fg));
            label.add(new Instr(Form.STRK, new BasicStroke(1 + (int) (height/10), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)));
            label.add(new Instr(Form.RRCT, new RoundRectangle2D.Double(lx, ly, width, height, height, height)));
            break;
        case VCLR:
            width += height * 1.0;
            height *= 2.0;
            if (width < height) width = height;
            lx = -width / 2;
            ly = -height / 2;
            tx = lx + (height * 0.27);
            ty = ly + (height * 0.25);
            label.add(new Instr(Form.BBOX, new Rectangle2D.Double(lx, ly, width, height)));
            label.add(new Instr(Form.FILL, bg));
            label.add(new Instr(Form.RSHP, new RoundRectangle2D.Double(lx, ly, width, height, height, height)));
            label.add(new Instr(Form.FILL, fg));
            int sw = 1 + (int) (height/10);
            double po = sw / 2;
            label.add(new Instr(Form.STRK, new BasicStroke(sw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)));
            Path2D.Double p = new Path2D.Double(); p.moveTo(-height*0.2, -ly-po);
            p.lineTo(height*0.2, -ly-po); p.moveTo(0, -ly-po); p.lineTo(0, -ly-po-(height*0.15));
            p.moveTo(-height*0.2, ly+po); p.lineTo((height*0.2), ly+po); p.moveTo(0, ly+po); p.lineTo(0, ly+po+(height*0.15));
            label.add(new Instr(Form.PLIN, p));
            break;
        case PCLR:
            width += height * 1.0;
            height *= 2.0;
            if (width < height) width = height;
            lx = -width / 2;
            ly = -height / 2;
            tx = lx + (height * 0.27);
            ty = ly + (height * 0.25);
            label.add(new Instr(Form.BBOX, new Rectangle2D.Double(lx, ly, width, height)));
            label.add(new Instr(Form.FILL, bg));
            label.add(new Instr(Form.RSHP, new RoundRectangle2D.Double(lx, ly, width, height, height, height)));
            label.add(new Instr(Form.FILL, fg));
            sw = 1 + (int) (height/10);
            po = sw / 2;
            label.add(new Instr(Form.STRK, new BasicStroke(sw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)));
            p = new Path2D.Double();
            p.moveTo(-height*0.2, -ly-po);
            p.lineTo(height*0.2, -ly-po);
            p.moveTo(0, -ly-po);
            p.lineTo(0, -ly-po-(height*0.15));
            p.moveTo(-height*0.2, ly+po);
            p.lineTo((height*0.2), ly+po);
            p.moveTo(0, ly+po);
            p.lineTo(0, ly+po+(height*0.15));
            label.add(new Instr(Form.PLIN, p));
            label.add(new Instr(Form.SYMB, new Symbols.SubSymbol(Areas.CableFlash, 1, 0, 0, null,
                    new Delta(Handle.CC, new AffineTransform(0, -1, 1, 0, -width/2, 0)))));
            label.add(new Instr(Form.SYMB, new Symbols.SubSymbol(Areas.CableFlash, 1, 0, 0, null,
                    new Delta(Handle.CC, new AffineTransform(0, -1, 1, 0, width/2, 0)))));
            break;
        case HCLR:
            width += height * 1.5;
            height *= 1.5;
            if (width < height) width = height;
            lx = -width / 2;
            ly = -height / 2;
            tx = lx + (height * 0.5);
            ty = ly + (height * 0.17);
            label.add(new Instr(Form.BBOX, new Rectangle2D.Double(lx, ly, width, height)));
            label.add(new Instr(Form.FILL, bg));
            label.add(new Instr(Form.RSHP, new RoundRectangle2D.Double(lx, ly, width, height, height, height)));
            label.add(new Instr(Form.FILL, fg));
            sw = 1 + (int) (height/10);
            double vo = height / 4;
            label.add(new Instr(Form.STRK, new BasicStroke(sw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)));
            p = new Path2D.Double();
            p.moveTo(-width*0.4-sw, -ly-vo);
            p.lineTo(-width*0.4-sw, ly+vo);
            p.moveTo(-width*0.4-sw, 0);
            p.lineTo(-width*0.4+sw, 0);
            p.moveTo(width*0.4+sw, -ly-vo);
            p.lineTo(width*0.4+sw, ly+vo);
            p.moveTo(width*0.4-sw, 0);
            p.lineTo(width*0.4+sw, 0);
            label.add(new Instr(Form.PLIN, p));
            break;
        default:
            lx = -width / 2;
            ly = -height / 2;
            tx = lx;
            ty = ly;
            label.add(new Instr(Form.BBOX, new Rectangle2D.Double(lx, ly, width, height)));
            break;
        }
        label.add(new Instr(Form.TEXT, new Caption(str, font, tc, new Delta(Handle.TL, AffineTransform.getTranslateInstance(tx, ty)))));
        Point2D point = context.getPoint(Rules.feature.geom.centre);
        Symbols.drawSymbol(g2, label, sScale, point.getX(), point.getY(), null, delta);
    }

    public static void lineText(String str, Font font, Color colour, double dy) {
        if (!str.isEmpty()) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setPaint(colour);
            FontRenderContext frc = g2.getFontRenderContext();
            GlyphVector gv = font.deriveFont(font.getSize2D() * (float) sScale).createGlyphVector(frc, str);
            double width = gv.getVisualBounds().getWidth();
            double height = gv.getVisualBounds().getHeight();
            double offset = (Rules.feature.geom.length * context.mile(Rules.feature) - width) / 2;
            if (offset > 0) {
                Point2D before = null;
                Point2D after = null;
                ArrayList<Point2D> between = new ArrayList<>();
                Point2D prev = null;
                Point2D next = null;
                double length = 0;
                double lb = 0;
                double la = 0;
                GeomIterator git = map.new GeomIterator(Rules.feature.geom);
                if (git.hasComp()) {
                    git.nextComp();
                    while (git.hasEdge()) {
                        git.nextEdge();
                        while (git.hasNode()) {
                            Snode node = git.next();
                            if (node == null)
                                continue;
                            prev = next;
                            next = context.getPoint(node);
                            if (prev != null)
                                length += Math.sqrt(Math.pow((next.getX() - prev.getX()), 2) + Math.pow((next.getY() - prev.getY()), 2));
                            if (length < offset) {
                                before = next;
                                lb = la = length;
                            } else if (after == null) {
                                if (length > (offset + width)) {
                                    after = next;
                                    la = length;
                                    break;
                                } else {
                                    between.add(next);
                                }
                            }
                        }
                        if (after != null)
                            break;
                    }
                }
                if (after != null) {
                    double angle = Math.atan2((after.getY() - before.getY()), (after.getX() - before.getX()));
                    double rotate = Math.abs(angle) < (Math.PI / 2) ? angle : angle + Math.PI;
                    Point2D mid = new Point2D.Double((before.getX() + after.getX()) / 2, (before.getY() + after.getY()) / 2);
                    Point2D centre = context.getPoint(Rules.feature.geom.centre);
                    AffineTransform pos = AffineTransform.getTranslateInstance(-dy * Math.sin(rotate), dy * Math.cos(rotate));
                    pos.rotate(rotate);
                    pos.translate((mid.getX() - centre.getX()), (mid.getY() - centre.getY()));
                    Symbol label = new Symbol();
                    label.add(new Instr(Form.BBOX, new Rectangle2D.Double((-width / 2), -height, width, height)));
                    label.add(new Instr(Form.TEXT, new Caption(str, font, colour, new Delta(Handle.BC))));
                    Symbols.drawSymbol(g2, label, sScale, centre.getX(), centre.getY(), null, new Delta(Handle.BC, pos));
                }
            }
        }
    }

    public static void lightSector(Color col1, Color col2, double radius, double s1, double s2, Double dir, String str) {
        double mid = (((s1 + s2) / 2) + (s1 > s2 ? 180 : 0)) % 360;
        g2.setStroke(new BasicStroke((float) (3.0 * sScale), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1,
                new float[] {20 * (float) sScale, 20 * (float) sScale}, 0));
        g2.setPaint(Color.black);
        Point2D.Double centre = (Point2D.Double) context.getPoint(Rules.feature.geom.centre);
        double radial = radius * context.mile(Rules.feature);
        if (dir != null) {
            g2.draw(new Line2D.Double(centre.x, centre.y, centre.x - radial * Math.sin(Math.toRadians(dir)),
                    centre.y + radial * Math.cos(Math.toRadians(dir))));
        } else {
            if ((s1 != 0.0) || (s2 != 360.0)) {
                g2.draw(new Line2D.Double(centre.x, centre.y, centre.x - radial * Math.sin(Math.toRadians(s1)),
                        centre.y + radial * Math.cos(Math.toRadians(s1))));
                g2.draw(new Line2D.Double(centre.x, centre.y, centre.x - radial * Math.sin(Math.toRadians(s2)),
                        centre.y + radial * Math.cos(Math.toRadians(s2))));
            }
        }
        double arcWidth = 10.0 * sScale;
        g2.setStroke(new BasicStroke((float) arcWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1));
        g2.setPaint(col1);
        g2.draw(new Arc2D.Double(centre.x - radial, centre.y - radial, 2 * radial, 2 * radial, -(s1 + 90),
                ((s1 < s2) ? (s1 - s2) : (s1 - s2 - 360)), Arc2D.OPEN));
        if (col2 != null) {
            g2.setPaint(col2);
            g2.draw(new Arc2D.Double(centre.x - radial + arcWidth, centre.y - radial + arcWidth, 2 * (radial - arcWidth),
                    2 * (radial - arcWidth), -(s1 + 90), ((s1 < s2) ? (s1 - s2) : (s1 - s2 - 360)), Arc2D.OPEN));
        }
        if (str != null && !str.isEmpty()) {
            Font font = new Font("Arial", Font.PLAIN, 40);
            double arc = (s2 > s1) ? (s2 - s1) : (s2 - s1 + 360);
            double awidth = (Math.toRadians(arc) * radial);
            boolean hand = ((mid > 270) || (mid < 90));
            double phi = Math.toRadians(mid);
            radial += 30 * sScale;
            AffineTransform at = AffineTransform.getTranslateInstance(-radial * Math.sin(phi) / sScale, radial * Math.cos(phi) / sScale);
            if ((font.getSize() * sScale * str.length()) < awidth) {
                at.rotate(Math.toRadians(mid + (hand ? 0 : 180)));
                labelText(str, font, Color.black, new Delta(Handle.CC, at));
            } else if ((font.getSize() * sScale) < awidth) {
                hand = (mid < 180);
                at.rotate(Math.toRadians(mid + (hand ? -90 : 90)));
                labelText(str, font, Color.black, hand ? new Delta(Handle.RC, at) : new Delta(Handle.LC, at));
            }
            if (dir != null) {
                font = new Font("Arial", Font.PLAIN, 30);
                str = dir + "°";
                hand = (dir > 180);
                phi = Math.toRadians(dir + (hand ? -0.5 : 0.5));
                radial -= 70 * sScale;
                at = AffineTransform.getTranslateInstance(-radial * Math.sin(phi) / sScale, radial * Math.cos(phi) / sScale);
                at.rotate(Math.toRadians(dir + (hand ? 90 : -90)));
                labelText(str, font, Color.black, hand ? new Delta(Handle.BR, at) : new Delta(Handle.BL, at));
            }
        }
    }
    
    public static void rasterPixel(double size, Color col) {
    	double s = Rules.feature.geom.centre.lat - (size / 2.0);
    	double w = Rules.feature.geom.centre.lon - (size / 2.0);
    	double n = Rules.feature.geom.centre.lat + (size / 2.0);
    	double e = Rules.feature.geom.centre.lon + (size / 2.0);
    	Point2D sw = context.getPoint(new Snode(s, w)); 
    	Point2D nw = context.getPoint(new Snode(n, w)); 
    	Point2D ne = context.getPoint(new Snode(n, e)); 
    	Point2D se = context.getPoint(new Snode(s, e)); 
        Symbol pixel = new Symbol();
        Path2D.Double path = new Path2D.Double(); path.moveTo(sw.getX(), sw.getY()); path.lineTo(nw.getX(), nw.getY());
        path.lineTo(ne.getX(), ne.getY()); path.lineTo(se.getX(), se.getY()); path.closePath();
        pixel.add(new Instr(Form.FILL, col));
        pixel.add(new Instr(Form.PGON, path));
        Symbols.drawSymbol(g2, pixel, 1.0, 0, 0, null, null);
    }
}
