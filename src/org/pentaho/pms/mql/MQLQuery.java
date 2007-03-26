/*
 * Copyright 2006 Pentaho Corporation.  All rights reserved. 
 * This software was developed by Pentaho Corporation and is provided under the terms 
 * of the Mozilla Public License, Version 1.1, or any later version. You may not use 
 * this file except in compliance with the license. If you need a copy of the license, 
 * please go to http://www.mozilla.org/MPL/MPL-1.1.txt. The Original Code is the Pentaho 
 * BI Platform.  The Initial Developer is Pentaho Corporation.
 *
 * Software distributed under the Mozilla Public License is distributed on an "AS IS" 
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or  implied. Please refer to 
 * the license for the specific language governing your rights and limitations.
*/
package org.pentaho.pms.mql;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.pentaho.pms.core.CWM;
import org.pentaho.pms.factory.CwmSchemaFactoryInterface;
import org.pentaho.pms.schema.BusinessColumn;
import org.pentaho.pms.schema.BusinessTable;
import org.pentaho.pms.schema.BusinessView;
import org.pentaho.pms.schema.OrderBy;
import org.pentaho.pms.schema.SchemaMeta;
import org.pentaho.pms.schema.WhereCondition;
import org.pentaho.pms.util.Const;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import be.ibridge.kettle.core.LogWriter;
import be.ibridge.kettle.core.Row;
import be.ibridge.kettle.core.database.DatabaseMeta;
import be.ibridge.kettle.core.exception.KettleException;
import be.ibridge.kettle.core.logging.Log4jStringAppender;
import be.ibridge.kettle.trans.Trans;
import be.ibridge.kettle.trans.TransMeta;
import be.ibridge.kettle.trans.step.RowListener;
import be.ibridge.kettle.trans.step.StepInterface;

public class MQLQuery {

	public static int MODEL_TYPE_RELATIONAL = 1;
	public static int MODEL_TYPE_OLAP = 2; // NOT SUPPORTED YET

	private int modelType = MODEL_TYPE_RELATIONAL;
	private List selections = new ArrayList();
	private List constraints = new ArrayList();
    private List order = new ArrayList();
    
	private BusinessView view;
	private String locale;
	private SchemaMeta schemaMeta;
  private CwmSchemaFactoryInterface cwmSchemaFactory;
	
	public MQLQuery( SchemaMeta schemaMeta, BusinessView view, String locale ) {
		this.schemaMeta = schemaMeta;
		this.view = view;
		this.locale = locale;
	}
	
	public MQLQuery( String XML, String locale, CwmSchemaFactoryInterface factory ) {
		// this.schemaMeta = schemaMeta;
		this.locale = locale;
    this.cwmSchemaFactory = factory;
		fromXML( XML );
	}
	
    public MQLQuery( String filename ) throws IOException
    {
        File file = new File(filename);
        FileInputStream fileInputStream = new FileInputStream(file);
        byte bytes[] = new byte[(int)file.length()];
        fileInputStream.read(bytes);
        fileInputStream.close();
        
        fromXML( new String(bytes, Const.XML_ENCODING) ) ;
    }
	
    public void save(String queryFile) throws IOException
    {
        File file = new File(queryFile);
        FileOutputStream fileOutputStream = new FileOutputStream(file);
        fileOutputStream.write(getXML().getBytes(Const.XML_ENCODING));
        fileOutputStream.close();
    }

	public void addSelection( BusinessColumn column ) {
		if( !selections.contains( column ) ) {
			selections.add( column );
		}
	}
	
	public List getSelections() {
		return selections;
	}
	
	public DatabaseMeta getDatabaseMeta() {
		if( selections.size() > 0 ) {
	        return ((BusinessColumn)selections.get(0)).getPhysicalColumn().getTable().getDatabaseMeta();
		}
		return null;
	}
	
    public void addConstraint( String operator, String columnId, String condition) {
    		BusinessColumn businessColumn = view.findBusinessColumn( columnId );
        addConstraint( operator, businessColumn, condition );
    }
	
	public void addConstraint( String operator, BusinessColumn businessColumn, String condition ) {
        
        if( businessColumn == null ) {
        		// TODO need to raise an error here, the table does not exist
        		return;
        }
        WhereCondition where = new WhereCondition( operator, businessColumn, condition);
        constraints.add( where );
	}
	
    public void addOrderBy( String tableId, String columnId, boolean ascending) {
        BusinessTable businessTable = view.findBusinessTable( tableId );
        if (businessTable == null) {
            // TODO need to raise an error here, the table does not exist
            return;
        }
        addOrderBy( businessTable, columnId, ascending );
	}
	
	public void addOrderBy( BusinessTable businessTable, String columnId, boolean ascending ) {
        
        BusinessColumn businessColumn = businessTable.findBusinessColumn(columnId);
        if (businessColumn == null) {
            // TODO need to raise an error here, the column does not exist
            return;
        }
            
        OrderBy orderBy = new OrderBy( businessColumn, ascending);
        order.add( orderBy );
	}
	
	public String getQuery( boolean useDisplayNames ) {
		if( view == null || selections.size() == 0 ) {
			return null;
		}
		BusinessColumn selection[] = (BusinessColumn[])selections.toArray(new BusinessColumn[selections.size()]);
		WhereCondition conditions[] = (WhereCondition[])constraints.toArray(new WhereCondition[constraints.size()]);
        OrderBy orderBy[] = (OrderBy[]) order.toArray(new OrderBy[order.size()]);
        
		return view.getSQL(selection, conditions, orderBy, locale, useDisplayNames);
	}

    public TransMeta getTransformation( boolean useDisplayNames ) {
        if( view == null || selections.size() == 0 ) {
            return null;
        }
        BusinessColumn selection[] = (BusinessColumn[])selections.toArray(new BusinessColumn[selections.size()]);
        WhereCondition conditions[] = (WhereCondition[])constraints.toArray(new WhereCondition[constraints.size()]);
        OrderBy orderBy[] = (OrderBy[]) order.toArray(new OrderBy[order.size()]);
        
        return view.getTransformationMeta(selection, conditions, orderBy, locale, useDisplayNames);
    }
    
    public List getRowsUsingTransformation( boolean useDisplayNames, StringBuffer logBuffer ) throws KettleException
    {
        final List list = new ArrayList();
        TransMeta transMeta = getTransformation(useDisplayNames);
        LogWriter log = LogWriter.getInstance();
        Log4jStringAppender stringAppender = LogWriter.createStringAppender();
        stringAppender.setBuffer(logBuffer);
        
        log.addAppender(stringAppender);
        Trans trans = new Trans(log, transMeta);
        trans.prepareExecution(null);
        for (int i=0;i<transMeta.getStep(0).getCopies();i++)
        {
            StepInterface stepInterface = trans.getStepInterface(transMeta.getStep(0).getName(), i);
            stepInterface.addRowListener(
                new RowListener()
                {
                    public void rowWrittenEvent(Row row) 
                    { 
                        list.add(row); // later: clone to be safe 
                    }  
                    public void rowReadEvent(Row row) { }
                    public void errorRowWrittenEvent(Row row) { }
                }
            );
        }
        trans.startThreads();
        trans.waitUntilFinished();
        log.removeAppender(stringAppender);
        
        if (trans.getErrors()>0) throw new KettleException("Error during query execution using a transformation:"+Const.CR+Const.CR+stringAppender.getBuffer().toString());
        
        return list;
        
    }
    
	public String getXML() {
		
        try {
            StringWriter stringWriter = new StringWriter();
            StreamResult result = new StreamResult();
            result.setWriter( stringWriter );
            TransformerFactory factory = TransformerFactory.newInstance();
            Document doc = getDocument();
        		if( doc != null ) {
            		factory.newTransformer().transform(new DOMSource(doc), result);
            		return stringWriter.getBuffer().toString();
        		}
        } catch (Exception e) {
        		e.printStackTrace();
        }
        return null;
	}
	
	public Document getDocument() {
        DocumentBuilderFactory dbf;
        DocumentBuilder db;
        Document doc;

        try {
            // create an XML document
            dbf = DocumentBuilderFactory.newInstance();
            db = dbf.newDocumentBuilder();
            doc = db.newDocument();
            Element mqlElement = doc.createElement( "mql" );
            doc.appendChild( mqlElement );

            if( addToDocument( mqlElement, doc) ) {
            		return doc;
            } else {
            		return null;
            }
        } catch (Exception e) {
        		e.printStackTrace();
        }
        return null;
	}
	
	public boolean addToDocument( Element mqlElement, Document doc ) {
		
        try {

	    		if( schemaMeta == null ) {
	        		System.err.println( "metadata schema is null" );
	        		return false;
	    		}
	    	
	    		if( view == null ) {
	        		System.err.println( "business view is null" );
	        		return false;
	    		}
	    	
    			// insert the model information
            	Element typeElement = doc.createElement( "model_type" );
            	typeElement.appendChild( doc.createTextNode( (modelType == MODEL_TYPE_RELATIONAL) ? "relational" : "olap" ) );
            	mqlElement.appendChild( typeElement );
        	
    			// insert the model information
            	String data = schemaMeta.getModelName();
            	if( data != null ) {
	            	Element modelIdElement = doc.createElement( "model_id" );
	            	modelIdElement.appendChild( doc.createTextNode( data ) );
	            	mqlElement.appendChild( modelIdElement );
            	} else {
            		System.err.println( "model id is null" );
	        		return false;
            	}
        	
            	// insert the view information
            	data = view.getId();
            	if( data != null ) {
                	Element viewIdElement = doc.createElement( "view_id" );
                	viewIdElement.appendChild( doc.createTextNode( data ) );
                	mqlElement.appendChild( viewIdElement );
            	} else {
            		System.err.println( "view id is null" );
	        		return false;
            	}
            
            	data = view.getDisplayName( locale );
            	if( data != null ) {
                	Element viewNameElement = doc.createElement( "view_name" );
                	viewNameElement.appendChild( doc.createTextNode( data ) );
                	mqlElement.appendChild( viewNameElement );
            	} else {
            		System.err.println( "view name is null" );
	        		return false;
            	}
            
            	// insert the selections
            	Element selectionsElement = doc.createElement( "selections" );
            	mqlElement.appendChild( selectionsElement );
            	Iterator it = selections.iterator();
            	Element selectionElement;
            	Element element;
            	while( it.hasNext() ) {
            		BusinessColumn column = (BusinessColumn) it.next();
            		if( column.getBusinessTable() != null ) {
                		selectionElement = doc.createElement( "selection" );
                		
                		element = doc.createElement( "table" );
                		element.appendChild( doc.createTextNode( column.getBusinessTable().getId() ) );
                		selectionElement.appendChild( element );
                		
                		element = doc.createElement( "column" );
                		element.appendChild( doc.createTextNode( column.getId() ) );
                		selectionElement.appendChild( element );
                		
                		selectionsElement.appendChild( selectionElement );
            		}
            	}
            	// insert the contraints
            	Element contraintsElement = doc.createElement( "constraints" );
            	mqlElement.appendChild( contraintsElement );
            	it = constraints.iterator();
            Element constraintElement;
            	while( it.hasNext() ) {
            		WhereCondition condition = (WhereCondition) it.next();
            		constraintElement = doc.createElement( "constraint" );

            		element = doc.createElement( "operator" );
            		element.appendChild( doc.createTextNode( condition.getOperator()==null?"":condition.getOperator() ) );
            		constraintElement.appendChild( element );
            		
                    element = doc.createElement( "table_id" );
                    element.appendChild( doc.createTextNode( condition.getField().getBusinessTable().getId() ) );
                    constraintElement.appendChild( element );
            		
            		element = doc.createElement( "column_id" );
            		element.appendChild( doc.createTextNode( condition.getField().getId() ) );
            		constraintElement.appendChild( element );
            		
            		element = doc.createElement( "condition" );
            		element.appendChild( doc.createTextNode( condition.getCondition() ) );
            		constraintElement.appendChild( element );
            		
                    // Save the localized names...
                    /* 
                     * TODO
                    String[] locales = condition.getConcept().getUsedLocale();
                    
                    element = doc.createElement( "name" );
                    for (int i=0;i<locales.length;i++) {
                        String name = condition.getName(locales[i]);
                        if (!Const.isEmpty( name) ) {
                            Element locElement = doc.createElement("locale");
                            locElement.appendChild( doc.createTextNode(locales[i]) );
                            element.appendChild(locElement);
                            Element valElement = doc.createElement("value");
                            locElement.appendChild( doc.createTextNode(name) );
                            element.appendChild(valElement);
                        }
                    }
                    constraintElement.appendChild( element );

                		element = doc.createElement( "description" );
                    for (int i=0;i<locales.length;i++) {
                        String description = condition.getDescription(locales[i]);
                        if (!Const.isEmpty( description) ) {
                            Element locElement = doc.createElement("locale");
                            locElement.appendChild( doc.createTextNode(locales[i]) );
                            element.appendChild(locElement);
                            Element valElement = doc.createElement("value");
                            locElement.appendChild( doc.createTextNode(description) );
                            element.appendChild(valElement);
            		}
                    }
                    constraintElement.appendChild( element );
                     */
            		
            		contraintsElement.appendChild( constraintElement );
            	}
            	// insert the contraints
            	Element ordersElement = doc.createElement( "orders" );
            	mqlElement.appendChild( ordersElement );
            	it = order.iterator();
            Element orderElement;
            	while( it.hasNext() ) {
            		OrderBy order = (OrderBy) it.next();
            		orderElement = doc.createElement( "order" );

            		element = doc.createElement( "direction" );
            		element.appendChild( doc.createTextNode( order.isAscending() ? "asc" : "desc" ) );
            		orderElement.appendChild( element );
            		
                    element = doc.createElement( "table_id" );
                    element.appendChild( doc.createTextNode( order.getBusinessColumn().getBusinessTable().getId() ) );
                    orderElement.appendChild( element );
            		
            		element = doc.createElement( "column_id" );
            		element.appendChild( doc.createTextNode( order.getBusinessColumn().getId() ) );
            		orderElement.appendChild( element );
            		
            		
            		ordersElement.appendChild( orderElement );
            	}
            
        	} catch (Exception e) {
        		e.printStackTrace();
        	}
    		return true;

	}
	
	public void fromXML( String XML ) {
        DocumentBuilderFactory dbf;
        DocumentBuilder db;
        Document doc;

        try {
            // Check and open XML document
            dbf = DocumentBuilderFactory.newInstance();
            db = dbf.newDocumentBuilder();
            doc = db.parse(new InputSource(new java.io.StringReader(XML)));

            // get the model type
            String modelTypeStr = getElementText( doc, "model_type" );
            if( "relational".equals( modelTypeStr ) ) {
            		modelType = MODEL_TYPE_RELATIONAL;
            }
            else if( "olap".equals( modelTypeStr ) ) {
            		modelType = MODEL_TYPE_OLAP;
            } else {
            		// need to throw an error
            }

            // get the model id
            String modelId = getElementText( doc, "model_id" );
            CWM cwm = CWM.getInstance(modelId);
            // CwmSchemaFactoryInterface cwmSchemaFactory = Settings.getCwmSchemaFactory();
            schemaMeta = cwmSchemaFactory.getSchemaMeta(cwm);
                        
            // get the Business View id
            String viewId = getElementText( doc, "view_id" );
            System.out.println( viewId );  
            view = schemaMeta.findView(viewId); // This is the business view that was selected.

            	if( view == null ) {
            		// TODO log this
            		return;
            	}
            
            // process the selections
            NodeList nodes = doc.getElementsByTagName( "selection" );
            Node selectionNode;
            for( int idx=0; idx<nodes.getLength(); idx++ ) {
            		selectionNode = nodes.item( idx );
            		addBusinessColumnFromXmlNode( selectionNode );
            }
            
            // process the constraints
            nodes = doc.getElementsByTagName( "constraint" );
            Node constraintNode;
            for( int idx=0; idx<nodes.getLength(); idx++ ) {
            		constraintNode = nodes.item( idx );
            		addConstraintFromXmlNode( constraintNode );
            }
            
            // process the constraints
            nodes = doc.getElementsByTagName( "order" );
            Node orderNode;
            for( int idx=0; idx<nodes.getLength(); idx++ ) {
            		orderNode = nodes.item( idx );
            		addOrderByFromXmlNode( orderNode );
            }
            
        } catch (Exception e) {
        		e.printStackTrace();
        }

	}
	
	private void addBusinessColumnFromXmlNode( Node node ) {
		NodeList nodes = node.getChildNodes();
		String columnId = nodes.item(1).getFirstChild().getNodeValue();
        BusinessColumn businessColumn = view.findBusinessColumn( columnId );
        if (businessColumn == null) {
            // TODO: throw some exception in the future.
            return;
        }
            
		addSelection( businessColumn );
	}
	
	
	private void addOrderByFromXmlNode( Node node ) {
		NodeList nodes = node.getChildNodes();
		boolean ascending  = nodes.item(0).getFirstChild()!=null ? nodes.item(0).getFirstChild().getNodeValue().equals( "asc" ) : true;
        String table_id  = nodes.item(1).getFirstChild().getNodeValue();
		String column_id = nodes.item(2).getFirstChild().getNodeValue();
        addOrderBy( table_id, column_id, ascending );
	}
	
	private void addConstraintFromXmlNode( Node node ) {
		NodeList nodes = node.getChildNodes();
		
		String operator  = nodes.item(0).getFirstChild()!=null ? nodes.item(0).getFirstChild().getNodeValue() : null;
        String table_id  = nodes.item(1).getFirstChild()!=null ? nodes.item(1).getFirstChild().getNodeValue() : null;
		String column_id = nodes.item(2).getFirstChild()!=null ? nodes.item(2).getFirstChild().getNodeValue() : null;
		String condition = nodes.item(3).getFirstChild()!=null ? nodes.item(3).getFirstChild().getNodeValue() : null;
        /*
         * TODO
		Node nameNode    = nodes.item(4).getFirstChild();
        Node descNode    = nodes.item(5).getFirstChild();
         */
		if( table_id != null && column_id != null && condition != null ) {
			addConstraint( operator, column_id, condition );
		}
	}
	
	private String getElementText( Document doc, String name ) {
		try {
			return doc.getElementsByTagName( name ).item(0).getFirstChild().getNodeValue();
		} catch (Exception e) {
			return null;
		}
	}
	
    
    
    
    /**
     * @return the constraints
     */
    public List getConstraints()
    {
        return constraints;
    }

    /**
     * @param constraints the constraints to set
     */
    public void setConstraints(List constraints)
    {
        this.constraints = constraints;
    }

    /**
     * @return the locale
     */
    public String getLocale()
    {
        return locale;
    }

    /**
     * @param locale the locale to set
     */
    public void setLocale(String locale)
    {
        this.locale = locale;
    }

    /**
     * @return the order
     */
    public List getOrder()
    {
        return order;
    }

    /**
     * @param order the order to set
     */
    public void setOrder(List order)
    {
        this.order = order;
    }

    /**
     * @return the schemaMeta
     */
    public SchemaMeta getSchemaMeta()
    {
        return schemaMeta;
    }

    /**
     * @param schemaMeta the schemaMeta to set
     */
    public void setSchemaMeta(SchemaMeta schemaMeta)
    {
        this.schemaMeta = schemaMeta;
    }

    /**
     * @return the view
     */
    public BusinessView getView()
    {
        return view;
    }

    /**
     * @param view the view to set
     */
    public void setView(BusinessView view)
    {
        this.view = view;
    }

    /**
     * @param selections the selections to set
     */
    public void setSelections(List selections)
    {
        this.selections = selections;
    }

    
    
}