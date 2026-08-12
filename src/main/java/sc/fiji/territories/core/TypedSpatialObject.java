package sc.fiji.territories.core;

/** Shared identity contract for 2D and 3D labelled objects. */
public interface TypedSpatialObject {

    int getIndex();

    int getTypeIndex();

    String getTypeName();

    long getLabel();
}

