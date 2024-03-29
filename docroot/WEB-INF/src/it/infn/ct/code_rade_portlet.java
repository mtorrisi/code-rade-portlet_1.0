/**************************************************************************
Copyright (c) 2011:
Istituto Nazionale di Fisica Nucleare (INFN), Italy
Consorzio COMETA (COMETA), Italy

See http://www.infn.it and and http://www.consorzio-cometa.it for details on
the copyright holders.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

@author <a href="mailto:mario.torrisi@ct.infn.it">Riccardo Bruno</a>(COMETA)
****************************************************************************/
package it.infn.ct;

// Import generic java libraries
import java.io.*;
import java.net.*;
import java.util.Iterator;
import java.util.List;
import java.util.Calendar;
import java.text.SimpleDateFormat;

// Importing portlet libraries
import javax.portlet.*;

// Importing liferay libraries
import com.liferay.portal.util.PortalUtil;
import com.liferay.portal.kernel.util.WebKeys;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.theme.ThemeDisplay;
import com.liferay.portal.model.Company;
import com.liferay.portal.model.User;

// Importing Apache libraries
import org.apache.commons.fileupload.*;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.portlet.PortletFileUpload;

// Importing GridEngine Job libraries
import it.infn.ct.GridEngine.Job.*;
import it.infn.ct.GridEngine.JobResubmission.GEJobDescription;
import it.infn.ct.GridEngine.Job.MultiInfrastructureJobSubmission;
import it.infn.ct.GridEngine.UsersTracking.UsersTrackingDBInterface;

// Logging
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *  This is the class that overrides the GenericPortlet class methods
 *  You can create your own application just customizing this code skeleton
 *  This code provides mainly a full working example on:
 *    1) How to manage user interaction managing the Actions/Viewa combination
 *    2) How to manage portlet preferences and help pane
 *    3) How to print application information using the Log object
 *    4) How to execute a distributed application with GridEngine
 *
 * @author <a href="mailto:mario.torrisi@ct.infn.it">Riccardo Bruno</a>(COMETA)
 */
public class code_rade_portlet extends GenericPortlet {

    // Instantiate the logger object
    AppLogger _log = new AppLogger(code_rade_portlet.class);

    // This portlet uses Aciont/Views enumerations in order to
    // manage the different portlet modes and the corresponding
    // view to display
    // You may override the current values with your own business
    // logic best identifiers and manage them through: jsp pages and
    // this java code
    // The jsp parameter PortletStatus will be the responsible of
    // portlet mode switching. This parameter will be read by
    // the processAction method (actionRequest) who will select
    // then the proper view mode. The doView method will read this
    // value (renderResponSe) assigning the correct view mode.
    //
    // At first boot the application will be in ACTIVATE status
    // that means the application still requires to be registered
    // into the GridEngine' UsersTrackingDB' GridOperations table
    // Once registered the defaul view mode will be the VIEW_INPUT

    /**
     * Actions enumeration contains the possible action status mode
     * managed by the application. Action modes are stored into the 'PortletStatus'
     * parameter inside the actionRequest object
     */
    private enum Actions {
         ACTION_ACTIVATE // User (Admin) activated the portlet
        ,ACTION_INPUT    // User asked to submit a job
        ,ACTION_SUBMIT   // User asked to rerutn to the input form
        ,ACTION_PILOT    // The user did something in the edit pilot screen pane
        }

    /**
     * Views enumeration contains the possible view mondes managed b
     * the application. View modes are stored into the parameter  'PortletStatus'
     * inside the renderResponse object
     */
    private enum Views {
         VIEW_ACTIVATE   // Show acrivation pane (called 1st time only)
        ,VIEW_INPUT      // View containing application input fields
        ,VIEW_SUBMIT     // View reporting the job submission
        ,VIEW_PILOT      // Shows the pilot script and makes it editable
    }

    /**
     * Instanciate the AppPreferences object that stores the Application preferences
     *
     * @see AppPreferences
     */
    AppPreferences appPreferences     = new AppPreferences(_log);
    AppPreferences appInitPreferences = new AppPreferences(_log);

    /**
     * This class contains all the necessary data to submit a job inside
     * a distributed infrastructure. Each submission will instanciate this
     * object
     */
    class AppInput {
        // Applicatoin inputs
        String inputFileName;   // Filename for application input file
        String inputFileText;   // Text for application input file
        String jobIdentifier;   // User' given job identifier

        // Each inputSandobox file must be declared below
        // This variable contains the content of an uploaded file
        String inputSandbox_inputFile;

        // Some user level information
        // must be stored as well
        String username;
        String timestamp;

        /**
         * Standard constructor just initialize empty values
         */
        public AppInput() {
            inputFileName
           =inputFileText
           =jobIdentifier
           =inputSandbox_inputFile
           =username
           =timestamp
           ="";
        }
    } // AppInput

    // Liferay portal data
    // Classes below are used by this portlet code to get information
    // about the current user
    public String       portalName="localhost";  // Name of the hosting portal
    public String       appServerPath;           // This variable stores the absolute path of the Web applications

    // Other misc valuse
    // (!) Pay attention that altough the use of the LS variable
    //     the replaceAll("\n","") has to be used
    public static final String LS = System.getProperty("line.separator");

    // Users must have separated inputSandbox files
    // these file will be generated into /tmp directory
    // and prefixed with the format <timestamp>_<user>_*
    // The timestamp format is:
    public static final String tsFormat = "yyyyMMddHHmmss";

    // This variable holds the GridEngine' GridOperation identifier
    // associated to this application
    int gridOperationId=-1;

    //----------------------------
    // Portlet Overriding Methods
    //----------------------------

    /**
     * The init method will be called when installing the portlet for the first time
     * or when restarting the portal server.
     * This is the right time to get default values from WEBINF/portlet.xml file
     * Those values will be assigned into the application preferences as default values
     * If preference values already exists for this application the default settings will
     * be overwritten
     *
     * @see AppInfrastructureInfo
     * @see AppPreferences
     *
     * @throws PortletException
     */
    @Override
    public void init()
    throws PortletException
    {
        // Load default values from WEBINF/portlet.xml
        appInitPreferences.setGridOperationDesc (""+getInitParameter( "gridOperationDesc"));
        appInitPreferences.setPortletVersion    (""+getInitParameter(    "portletVersion"));
        appInitPreferences.setLogLevel          (""+getInitParameter(          "logLevel"));
        appInitPreferences.setNumInfrastructures(""+getInitParameter("numInfrastructures"));
        appInitPreferences.setGridOperationId   (""+getInitParameter(   "gridOperationId"));
        // Get the number of infrastructures to load
        int numInfra=appInitPreferences.getNumInfrastructures();
        _log.info("Number of infrastructures: '"+numInfra+"'");
        // Load infrastructure settings
        for(int i=0; i<numInfra; i++) {
            int j=i+1;
            appInitPreferences.setInfrastructure(
                      i
                    , ""+getInitParameter(j+ "_enableInfrastructure")
                    , ""+getInitParameter(j+   "_nameInfrastructure")
                    , ""+getInitParameter(j+"_acronymInfrastructure")
                    , ""+getInitParameter(j+             "_bdiiHost")
                    , ""+getInitParameter(j+             "_wmsHosts")
                    , ""+getInitParameter(j+         "_pxServerHost")
                    , ""+getInitParameter(j+         "_pxServerPort")
                    , ""+getInitParameter(j+       "_pxServerSecure")
                    , ""+getInitParameter(j+            "_pxRobotId")
                    , ""+getInitParameter(j+            "_pxRobotVO")
                    , ""+getInitParameter(j+          "_pxRobotRole")
                    , ""+getInitParameter(j+   "_pxRobotRenewalFlag")
                    , ""+getInitParameter(j+          "_pxUserProxy")
                    , ""+getInitParameter(j+         "_softwareTags")
                   );
        } // Load infrastructure settings
        appInitPreferences.setSciGwyUserTrackingDB_Hostname(""+getInitParameter("sciGwyUserTrackingDB_Hostname"));
        appInitPreferences.setSciGwyUserTrackingDB_Username(""+getInitParameter("sciGwyUserTrackingDB_Username"));
        appInitPreferences.setSciGwyUserTrackingDB_Password(""+getInitParameter("sciGwyUserTrackingDB_Password"));
        appInitPreferences.setSciGwyUserTrackingDB_Database(""+getInitParameter("sciGwyUserTrackingDB_Database"));
        appInitPreferences.setJobRequirements              (""+getInitParameter(              "jobRequirements"));
        appInitPreferences.setPilotScript                  (""+getInitParameter(                  "pilotScript"));

        // Assigns the log level
        _log.setLogLevel(appInitPreferences.getLogLevel());

        // Show loaded values into log
        _log.info(appInitPreferences.dump());
    } // init

    /**
     * This method allows the portlet to process an action request; this method is normally
     * called upon each user interaction (i.e. A submit button inside a jsp' <form statement)
     * This method determines the current application mode through the actionRequest value:
     * 'PortletStatus' and then determines the correct view mode to assign through the
     * ActionResponse 'PortletStatus' variable that will be read by the doView
     * This method will also takes care about the std JSR168/286: EDIT and HELP portlet modes.
     *
     * @param request  ActionRequest object instance
     * @param response ActionResponse object instance
     *
     * @throws PortletException
     * @throws IOException
     */
    @Override
    public void processAction(ActionRequest request, ActionResponse response)
        throws PortletException, IOException
    {
        _log.info("calling processAction ...");

        // Determine the username
        ThemeDisplay themeDisplay = (ThemeDisplay)request.getAttribute(WebKeys.THEME_DISPLAY);
        User                 user = themeDisplay.getUser();
        String           username = user.getScreenName();
        // Determine the application pathname
        PortletSession portletSession = request.getPortletSession();
        PortletContext portletContext = portletSession.getPortletContext();
        appServerPath                 = portletContext.getRealPath("/");
        // Show info
        _log.info("appUserName   : '"+username     +"'"
              +LS+"appServerPath : '"+appServerPath+"'"
                );
        // Determine the current portlet mode and forward this state to the response
        // Accordingly to JSRs168/286 the standard portlet modes are:
        // VIEW, EDIT, HELP
        PortletMode mode = request.getPortletMode();
        response.setPortletMode(mode);

        // Switch among different portlet modes: VIEW, EDIT, HELP
        // any custom modes are not covered by this template

        //----------
        // VIEW Mode
        //
        // The actionStatus value will be taken from the calling jsp file
        // through the 'PortletStatus' parameter; the corresponding
        // VIEW mode will be stored registering the portlet status
        // as render parameter. See the call to setRenderParameter
        // If the actionStatus parameter is null or empty the default
        // action will be the ACTION_INPUT (input form)
        // This happens the first time the portlet is shown
        // The PortletStatus variable is managed by jsp and this java code
        //----------
        if (mode.equals(PortletMode.VIEW)) {
            // The VIEW mode is the normal portlet mode where normal portlet
            // content will be shown to the user
            _log.info("Portlet mode: VIEW");

            String actionStatus=request.getParameter("PortletStatus");
            // Assigns the default ACTION mode
            if(   null==actionStatus
               || actionStatus.equals(""))
            	actionStatus=""+Actions.ACTION_INPUT;

            // Different actions will be performed accordingly to the
            // different possible statuses
            switch(Actions.valueOf(actionStatus)) {
                case ACTION_ACTIVATE:
                    _log.info("Got action: 'ACTION_ACTIVATE'");
                    // Called when activating the portlet for the first time
                    // it will be used to save the gridOperationId value
                    // into the application preferences
                    gridOperationId=Integer.parseInt(request.getParameter("gridOperationId"));
                    _log.info("Received gridOperationId: '"+gridOperationId+"'");
                    // If the application is registered go to the VIEW_INPUT
                    // and the application will no longer go to the ACTIVATE pane
                    if(gridOperationId > 0) {
                        storePreferences(request);
                        response.setRenderParameter("PortletStatus",""+Views.VIEW_INPUT);
                    }
                break;
                case ACTION_INPUT:
                    _log.info("Got action: 'ACTION_INPUT'");

                    // Assign the correct view
                    response.setRenderParameter("PortletStatus",""+Views.VIEW_INPUT);
                break;
                case ACTION_PILOT:
                    _log.info("Got action: 'ACTION_PILOT'");
                    // Stores the new pilot script
                    String pilotScript=request.getParameter("pilotScript");
                    pilotScript.replaceAll("\r", "");
                    storeString(appServerPath+"WEB-INF/job/"+appPreferences.getPilotScript(),pilotScript);
                    // Assign the correct view
                    response.setPortletMode(PortletMode.EDIT);
                break;
                case ACTION_SUBMIT:
                    _log.info("Got action: 'ACTION_SUBMIT'");

                    // Get current preference values
                    getPreferences(request,null);

                    // Create the appInput object
                    AppInput appInput = new AppInput();

                    // Stores the user submitting the job
                    appInput.username=username;

                    // Determine the submissionTimeStamp
                    SimpleDateFormat dateFormat = new SimpleDateFormat(tsFormat);
                    String timestamp = dateFormat.format(Calendar.getInstance().getTime());
                    appInput.timestamp=timestamp;

                    // Process input fields and files to upload
                    getInputForm(request,appInput);

                    // Following files have to be updated with
                    // values taken from textareas or from uploaded files:
                    // input_file.txt
                    updateFiles(appInput);

                    // Submit the job
                    submitJob(appInput);

                    // Send the jobIdentifier and assign the correct view
                    response.setRenderParameter("PortletStatus",""+Views.VIEW_SUBMIT);
                    response.setRenderParameter("jobIdentifier",""+appInput.jobIdentifier);
                break;
                default:
                     _log.info("Unhandled action: '"+actionStatus+"'");
                     response.setRenderParameter("PortletStatus",""+Views.VIEW_INPUT);
            } // switch actionStatus
        } // VIEW
        //----------
        // HELP Mode
        //
        // The HELP mode used to give portlet usage HELP to the user
        // This code will be called after the call to doHelp method
        //----------
        else if(mode.equals(PortletMode.HELP)) {
            _log.info("Portlet mode: HELP");
        }
        //----------
        // EDIT Mode
        //
        // The EDIT mode is used to view/setup portlet preferences
        // This code will be called after the user sends the actionURL
        // generated by the doEdit method
        // The code below just stores new preference values or
        // reacts to the preference settings changes
        //----------
        else if(mode.equals(PortletMode.EDIT)) {
            _log.info("Portlet mode: EDIT");

            // Retrieve the current ifnrstructure in preference
            int numInfrastructures=appPreferences.getNumInfrastructures    ();
            int currInfra         =appPreferences.getCurrPaneInfrastructure();

            _log.info(
                   LS+"Number of infrastructures: '"+numInfrastructures+"'"
                  +LS+"currentInfrastructure:     '"+currInfra         +"'"
                  +LS);

            // Take care of the preference action (Infrastructure preferences)
            // <,>,+,- buttons
            String pref_action=""+request.getParameter("pref_action");
            _log.info("pref_action: '"+pref_action+"'");

            // Reacts to the current infrastructure change and
            // determine the next view mode (return to the input pane)
            if(pref_action.equalsIgnoreCase("next")) {
                appPreferences.switchNextInfrastructure();
                _log.info("Got next infrastructure action; switching to: '"+appPreferences.getCurrPaneInfrastructure()+"'");
            }
            else if(pref_action.equalsIgnoreCase("previous")) {
                appPreferences.switchPreviousInfrastructure();
                _log.info("Got prev infrastructure action; switching to: '"+appPreferences.getCurrPaneInfrastructure()+"'");
            }
            else if(pref_action.equalsIgnoreCase("add")) {
                appPreferences.addNewInfrastructure();
                _log.info("Got add infrastructure action; current infrastrucure is now: '"+appPreferences.getCurrPaneInfrastructure()+"'");
            }
            else if(pref_action.equalsIgnoreCase("remove")) {
                appPreferences.delCurrInfrastructure();
                _log.info("Got remove infrastructure action; current infrastrucure is now: '"+appPreferences.getCurrPaneInfrastructure()+"' and infrastructures are now: '"+appPreferences.getNumInfrastructures()+"'");
            }
            else if(pref_action.equalsIgnoreCase("done")) {
                // None of the above actions selected; return to the VIEW mode
                response.setPortletMode(PortletMode.VIEW);
                response.setRenderParameter("PortletStatus", ""+Views.VIEW_INPUT);
            }
            else if(pref_action.equalsIgnoreCase("viewPilot")) {
                // None of the above actions selected; return to the VIEW mode
                response.setPortletMode(PortletMode.VIEW);
                response.setRenderParameter("PortletStatus",""+Views.VIEW_PILOT);
                response.setRenderParameter("pilotScript"
                                            ,updateString(appServerPath+"WEB-INF/job/"+appPreferences.getPilotScript()));
            }
            else {
                // No other special actions to do ...
            }

            // Number of infrastructures and Currentinfrastructure values
            // may be changed by add/delete,<,> actions
            int newCurrInfra         =appPreferences.getCurrPaneInfrastructure();
            int newNumInfrastructures=appPreferences.getNumInfrastructures    ();

            // Store infrastructure changes
            String infrastructuresInformations="";

            // Preference settings (logLevel has been taken above)
            String newpref_logLevel        = ""+request.getParameter(       "pref_logLevel");
            String newpref_gridOperationId = ""+request.getParameter("pref_gridOperationId");
            String newpref_jobRequirements = ""+request.getParameter("pref_jobRequirements");
            String newpref_pilotScript     = ""+request.getParameter(    "pref_pilotScript");
//LIC
            String newpref_sciGwyUserTrackingDB_Hostname = ""+request.getParameter("pref_sciGwyUserTrackingDB_Hostname");
            String newpref_sciGwyUserTrackingDB_Username = ""+request.getParameter("pref_sciGwyUserTrackingDB_Username");
            String newpref_sciGwyUserTrackingDB_Password = ""+request.getParameter("pref_sciGwyUserTrackingDB_Password");
            String newpref_sciGwyUserTrackingDB_Database = ""+request.getParameter("pref_sciGwyUserTrackingDB_Database");

            // Store infrastructure changes only if the user did not select the delete button
            if(newNumInfrastructures >= numInfrastructures) {
                // Current infrastructure preference settings
                AppInfrastructureInfo newpref_appInfrastructureInfo=new AppInfrastructureInfo(
                      ""+request.getParameter( "pref_enableInfrastructure")
                    , ""+request.getParameter(   "pref_nameInfrastructure")
                    , ""+request.getParameter("pref_acronymInfrastructure")
                    , ""+request.getParameter(             "pref_bdiiHost")
                    , ""+request.getParameter(             "pref_wmsHosts")
                    , ""+request.getParameter(         "pref_pxServerHost")
                    , ""+request.getParameter(         "pref_pxServerPort")
                    , ""+request.getParameter(       "pref_pxServerSecure")
                    , ""+request.getParameter(            "pref_pxRobotId")
                    , ""+request.getParameter(            "pref_pxRobotVO")
                    , ""+request.getParameter(          "pref_pxRobotRole")
                    , ""+request.getParameter(   "pref_pxRobotRenewalFlag")
                    , ""+request.getParameter(          "pref_pxUserProxy")
                    , ""+request.getParameter(         "pref_softwareTags")
                    );
                // newNumInfrastructures == numInfrastructures
                // the user selected < or > buttons; changes goes to the old (currInfra) value
                // otherwise + has been selected; changes goes again on the old (currInfra) value
                // the - case has been filtered out by newNumInfrastructures >= numInfrastructures
                String    pref_enableInfrastructure =appPreferences.getEnableInfrastructure (currInfra-1);
                String    pref_nameInfrastructure   =appPreferences.getNameInfrastructure   (currInfra-1);
                String    pref_acronymInfrastructure=appPreferences.getAcronymInfrastructure(currInfra-1);
                String    pref_bdiiHost             =appPreferences.getBdiiHost             (currInfra-1);
                String    pref_wmsHosts             =appPreferences.getWmsHosts             (currInfra-1);
                String    pref_pxServerHost         =appPreferences.getPxServerHost         (currInfra-1);
                String    pref_pxServerPort         =appPreferences.getPxServerPort         (currInfra-1);
                String    pref_pxServerSecure       =appPreferences.getPxServerSecure       (currInfra-1);
                String    pref_pxRobotId            =appPreferences.getPxRobotId            (currInfra-1);
                String    pref_pxRobotVO            =appPreferences.getPxRobotVO            (currInfra-1);
                String    pref_pxRobotRole          =appPreferences.getPxRobotRole          (currInfra-1);
                String    pref_pxRobotRenewalFlag   =appPreferences.getPxRobotRenewalFlag   (currInfra-1);
                String    pref_pxUserProxy          =appPreferences.getPxUserProxy          (currInfra-1);
                String    pref_softwareTags         =appPreferences.getSoftwareTags         (currInfra-1);
                // New preference values
                String newpref_enableInfrastructure =newpref_appInfrastructureInfo.getEnableInfrastructure ();
                String newpref_nameInfrastructure   =newpref_appInfrastructureInfo.getNameInfrastructure   ();
                String newpref_acronymInfrastructure=newpref_appInfrastructureInfo.getAcronymInfrastructure();
                String newpref_bdiiHost             =newpref_appInfrastructureInfo.getBdiiHost             ();
                String newpref_wmsHosts             =newpref_appInfrastructureInfo.getWmsHosts             ();
                String newpref_pxServerHost         =newpref_appInfrastructureInfo.getPxServerHost         ();
                String newpref_pxServerPort         =newpref_appInfrastructureInfo.getPxServerPort         ();
                String newpref_pxServerSecure       =newpref_appInfrastructureInfo.getPxServerSecure       ();
                String newpref_pxRobotId            =newpref_appInfrastructureInfo.getPxRobotId            ();
                String newpref_pxRobotVO            =newpref_appInfrastructureInfo.getPxRobotVO            ();
                String newpref_pxRobotRole          =newpref_appInfrastructureInfo.getPxRobotRole          ();
                String newpref_pxRobotRenewalFlag   =newpref_appInfrastructureInfo.getPxRobotRenewalFlag   ();
                String newpref_pxUserProxy          =newpref_appInfrastructureInfo.getPxUserProxy          ();
                String newpref_softwareTags         =newpref_appInfrastructureInfo.getSoftwareTags         ();
                // Prepare the Log string with differences
                infrastructuresInformations+=
                    LS+"Infrastructure #"+currInfra
                    +LS+"  enableInfrastructure  : '"+pref_enableInfrastructure +"' -> '"+newpref_enableInfrastructure +"'"
                    +LS+"  nameInfrastructures   : '"+pref_nameInfrastructure   +"' -> '"+newpref_nameInfrastructure   +"'"
                    +LS+"  acronymInfrastructures: '"+pref_acronymInfrastructure+"' -> '"+newpref_acronymInfrastructure+"'"
                    +LS+"  bdiiHost              : '"+pref_bdiiHost             +"' -> '"+newpref_bdiiHost             +"'"
                    +LS+"  wmsHosts              : '"+pref_wmsHosts             +"' -> '"+newpref_wmsHosts             +"'"
                    +LS+"  pxServerHost          : '"+pref_pxServerHost         +"' -> '"+newpref_pxServerHost         +"'"
                    +LS+"  pxServerPort          : '"+pref_pxServerPort         +"' -> '"+newpref_pxServerPort         +"'"
                    +LS+"  pxServerSecure        : '"+pref_pxServerSecure       +"' -> '"+newpref_pxServerSecure       +"'"
                    +LS+"  pxRobotId             : '"+pref_pxRobotId            +"' -> '"+newpref_pxRobotId            +"'"
                    +LS+"  pxRobotVO             : '"+pref_pxRobotVO            +"' -> '"+newpref_pxRobotVO            +"'"
                    +LS+"  pxRobotRole           : '"+pref_pxRobotRole          +"' -> '"+newpref_pxRobotRole          +"'"
                    +LS+"  pxRobotRenewalFlag    : '"+pref_pxRobotRenewalFlag   +"' -> '"+newpref_pxRobotRenewalFlag   +"'"
                    +LS+"  pxUserProxy           : '"+pref_pxUserProxy          +"' -> '"+newpref_pxUserProxy          +"'"
                    +LS+"  softwareTags          : '"+pref_softwareTags         +"' -> '"+newpref_softwareTags         +"'"
                    +LS;
                // Assigns the new values
                appPreferences.setInfrastructure(
                        currInfra-1
                        , newpref_enableInfrastructure
                        , newpref_nameInfrastructure
                        , newpref_acronymInfrastructure
                        , newpref_bdiiHost
                        , newpref_wmsHosts
                        , newpref_pxServerHost
                        , newpref_pxServerPort
                        , newpref_pxServerSecure
                        , newpref_pxRobotId
                        , newpref_pxRobotVO
                        , newpref_pxRobotRole
                        , newpref_pxRobotRenewalFlag
                        , newpref_pxUserProxy
                        , newpref_softwareTags
                        );
            } // newCurrInfra >= currInfra
            // Show preference value changes
            _log.info(
                    LS+"variable name          : 'Old Value' -> 'New value'"
                +LS+"---------------------------------------------------"
                +LS+"pref_logLevel                      : '"+appPreferences.getLogLevel                     ()+"' -> '"+newpref_logLevel                     +"'"
                +LS+"pref_gridOperationId               : '"+appPreferences.getGridOperationId              ()+"' -> '"+newpref_gridOperationId              +"'"
                +LS+"pref_numInfrastructures            : '"+appPreferences.getNumInfrastructures           ()+"' -> '"+numInfrastructures                   +"'"
                +LS+infrastructuresInformations
                +LS+"pref_jobRequirements               : '"+appPreferences.getJobRequirements              ()+"' -> '"+newpref_jobRequirements              +"'"
                +LS+"pref_pilotScript                   : '"+appPreferences.getPilotScript                  ()+"' -> '"+newpref_pilotScript                  +"'"
                +LS+"pref_sciGwyUserTrackingDB_Hostname : '"+appPreferences.getSciGwyUserTrackingDB_Hostname()+"' -> '"+newpref_sciGwyUserTrackingDB_Hostname+"'"
                +LS+"pref_sciGwyUserTrackingDB_Username : '"+appPreferences.getSciGwyUserTrackingDB_Username()+"' -> '"+newpref_sciGwyUserTrackingDB_Username+"'"
                +LS+"pref_sciGwyUserTrackingDB_Password : '"+appPreferences.getSciGwyUserTrackingDB_Password()+"' -> '"+newpref_sciGwyUserTrackingDB_Password+"'"
                +LS+"pref_sciGwyUserTrackingDB_Database : '"+appPreferences.getSciGwyUserTrackingDB_Database()+"' -> '"+newpref_sciGwyUserTrackingDB_Database+"'"
                +LS);

            // Assign the new variable to the preference object
            appPreferences.setLogLevel       (       newpref_logLevel);
            appPreferences.setGridOperationId(newpref_gridOperationId);
            appPreferences.setJobRequirements(newpref_jobRequirements);
            appPreferences.setPilotScript    (    newpref_pilotScript);
//LIC
            appPreferences.setSciGwyUserTrackingDB_Hostname(newpref_sciGwyUserTrackingDB_Hostname);
            appPreferences.setSciGwyUserTrackingDB_Username(newpref_sciGwyUserTrackingDB_Username);
            appPreferences.setSciGwyUserTrackingDB_Password(newpref_sciGwyUserTrackingDB_Password);
            appPreferences.setSciGwyUserTrackingDB_Database(newpref_sciGwyUserTrackingDB_Database);

            // Store new preferences
            storePreferences(request);
        } // EDIT Mode
        //----------
        // EDIT Mode
        //
        // Any custom portlet mode should be placed here below
        //----------
        else {
            // Unsupported portlet modes come here
            _log.warn("Custom portlet mode: '"+mode.toString()+"'");
        } // CUSTOM Mode
    } // processAction

    /**
     * This method is responsible to assign the correct Application view
     * the view mode is taken from the renderRequest instance by the PortletStatus patameter
     * or automatically assigned accordingly to the Application status/default view mode
     *
     * @param request RenderRequest instance normally sent by the processAction
     * @param response RenderResponse used to send values to the jsp page
     *
     * @throws PortletException
     * @throws IOException
     */
    @Override
    protected void doView(RenderRequest request, RenderResponse response)
    throws PortletException, IOException
    {
        _log.info("calling doView ...");
        response.setContentType("text/html");

        // Get current preference values
        getPreferences(null,request);
        gridOperationId=Integer.parseInt(appPreferences.getGridOperationId());
        _log.info("GridOperationId: '"+gridOperationId+"'");
        // currentView comes from the processAction; unless such method
        // is not called before (example: page shown with no user action)
        // In case the application is not yet register (gridOperationId<0)
        // the VIEW_INITIALIZE pane will be enforced otherwise the
        // VIEW_INPUT will be selected as default view
        String currentView=request.getParameter("PortletStatus");
        if(currentView==null) currentView="VIEW_INPUT";
        if(gridOperationId<0) currentView="VIEW_ACTIVATE";

        // Different actions will be performed accordingly to the
        // different possible view modes
        switch(Views.valueOf(currentView)) {
            // The following code is responsible to call the proper jsp file
            // that will provide the correct portlet interface
            case VIEW_ACTIVATE: {
                _log.info("VIEW_ACTIVATE Selected ...");
                /*
                 * Following statements requires a patch on the GridEngine not yet included and related
                 * to the application auto registration feature (see code appAutoRegistration.java)
                 *
                // Portlet uses the couple (portalName,GridOperationDesc)
                // to be identified by the GridEngine UserTrackingDB
                // VIEW_INITIALIZE checks if the Application is already
                // registered or not
                try {
                  UsersTrackingDBInterface utDB              = new UsersTrackingDBInterface();
                  Company                  company           = PortalUtil.getCompany(request);
                                           portalName        = company.getName();
                  String                   operationDesc     = appPreferences.getGridOperationDesc();
                  int                      utId              = utDB.registerOperation(portalName, operationDesc);
                  PortletPreferences       portletPreferences= request.getPreferences();

                  // Show values ...
                  _log.info(LS+"Check configuration"
                           +LS+"--------------------"
                           +LS+"utId          : '"+utId+"'"
                           +LS+"portalName    : '"+portalName+"'"
                           +LS+"operationDesc : '"+appPreferences.getGridOperationDesc()+"'"
                           ); // _log
                  // Show the registration page
                  request.setAttribute("gridOperationDesc", operationDesc);
                  request.setAttribute(  "gridOperationId",          utId);
                  request.setAttribute(           "portal",    portalName);
                  request.setAttribute(   "appPreferences",appPreferences);
                  PortletRequestDispatcher dispatcher=getPortletContext().getRequestDispatcher("/activate.jsp");
                  dispatcher.include(request, response);
                }
                catch (PortalException ex) {
                  _log.error("Got exception: '"+ex.toString()+"'");
                }
                catch (SystemException ex) {
                  _log.error("Got exception: '"+ex.toString()+"'");
                }
                * Application autoregistration feature
                */
            }
            break;
            case VIEW_INPUT: {
                _log.info("VIEW_INPUT Selected ...");
                PortletRequestDispatcher dispatcher=getPortletContext().getRequestDispatcher("/input.jsp");
                dispatcher.include(request, response);
            }
            break;
            case VIEW_PILOT: {
                _log.info("VIEW_PILOT Selected ...");
                String pilotScript = request.getParameter("pilotScript");
                request.setAttribute("pilotScript", pilotScript);
                PortletRequestDispatcher dispatcher=getPortletContext().getRequestDispatcher("/viewPilot.jsp");
                dispatcher.include(request, response);
            }
            break;
            case VIEW_SUBMIT: {
                _log.info("VIEW_SUBMIT Selected ...");
                String jobIdentifier = request.getParameter("jobIdentifier");
                request.setAttribute("jobIdentifier", jobIdentifier);
                PortletRequestDispatcher dispatcher=getPortletContext().getRequestDispatcher("/submit.jsp");
                dispatcher.include(request, response);
            }
            break;
            default:
                _log.info("Unknown view mode: "+currentView.toString());
        } // switch
    } // doView

    /**
     * This method is responsible to retrieve the current Application preference settings
     * and then show the edit.jsp page where the user can edit the Application preferences
     * This methods prepares an actionURL that will be used by edit.jsp file into a <input ...> form
     * As soon the user press the action button the processAction will be called going in EDIT mode
     * This method is equivalent to the doView method
     *
     * @param request Render request object instance
     * @param response Render response object isntance
     *
     * @throws PortletException
     * @throws IOException
     *
     */
    @Override
    public void doEdit(RenderRequest request,RenderResponse response)
    throws PortletException,IOException {
        response.setContentType("text/html");
        _log.info("Calling doEdit ...");

        // Get current preference values
        getPreferences(null,request);

        // Get the current infrastructure and the number of infrastructure
        int currInfra         =appPreferences.getCurrPaneInfrastructure();
        int numInfrastructures=appPreferences.getNumInfrastructures();

        // ActionURL and the current preference value will be passed to the edit.jsp
        PortletURL pref_actionURL = response.createActionURL();
        request.setAttribute("pref_actionURL",pref_actionURL.toString());

        // Send preference values
        request.setAttribute("pref_logLevel"          ,""+appPreferences.getLogLevel              ());
        request.setAttribute("pref_numInfrastructures",""+appPreferences.getNumInfrastructures    ());
        request.setAttribute("pref_currInfrastructure",""+appPreferences.getCurrPaneInfrastructure());
        request.setAttribute("pref_gridOperationId"   ,""+appPreferences.getGridOperationId       ());
        request.setAttribute("pref_gridOperationDesc" ,""+appPreferences.getGridOperationDesc     ());
        // Send Infrastructure specific data
        if(   0<= currInfra
           &&     currInfra <= numInfrastructures) {
          request.setAttribute("pref_enableInfrastructure" ,appPreferences.getEnableInfrastructure (currInfra-1));
          request.setAttribute("pref_nameInfrastructure"   ,appPreferences.getNameInfrastructure   (currInfra-1));
          request.setAttribute("pref_acronymInfrastructure",appPreferences.getAcronymInfrastructure(currInfra-1));
          request.setAttribute("pref_bdiiHost"             ,appPreferences.getBdiiHost             (currInfra-1));
          request.setAttribute("pref_wmsHosts"             ,appPreferences.getWmsHosts             (currInfra-1));
          request.setAttribute("pref_pxServerHost"         ,appPreferences.getPxServerHost         (currInfra-1));
          request.setAttribute("pref_pxServerPort"         ,appPreferences.getPxServerPort         (currInfra-1));
          request.setAttribute("pref_pxServerSecure"       ,appPreferences.getPxServerSecure       (currInfra-1));
          request.setAttribute("pref_pxRobotId"            ,appPreferences.getPxRobotId            (currInfra-1));
          request.setAttribute("pref_pxRobotVO"            ,appPreferences.getPxRobotVO            (currInfra-1));
          request.setAttribute("pref_pxRobotRole"          ,appPreferences.getPxRobotRole          (currInfra-1));
          request.setAttribute("pref_pxRobotRenewalFlag"   ,appPreferences.getPxRobotRenewalFlag   (currInfra-1));
          request.setAttribute("pref_pxUserProxy"          ,appPreferences.getPxUserProxy          (currInfra-1));
          request.setAttribute("pref_softwareTags"         ,appPreferences.getSoftwareTags         (currInfra-1));
        } // if paneInfrastructure > 0
        request.setAttribute("pref_jobRequirements"   ,appPreferences.getJobRequirements());
        request.setAttribute("pref_pilotScript"       ,appPreferences.getPilotScript    ());
//LIC
        request.setAttribute("pref_sciGwyUserTrackingDB_Hostname",appPreferences.getSciGwyUserTrackingDB_Hostname());
        request.setAttribute("pref_sciGwyUserTrackingDB_Username",appPreferences.getSciGwyUserTrackingDB_Username());
        request.setAttribute("pref_sciGwyUserTrackingDB_Password",appPreferences.getSciGwyUserTrackingDB_Password());
        request.setAttribute("pref_sciGwyUserTrackingDB_Database",appPreferences.getSciGwyUserTrackingDB_Database());

        // The edit.jsp will be the responsible to show/edit the current preference values
        PortletRequestDispatcher dispatcher=getPortletContext().getRequestDispatcher("/edit.jsp");
        dispatcher.include(request, response);
    } // doEdit

    /**
     * This method just calls the jsp responsible to show the portlet information
     * This method is equivalent to the doView method
     *
     * @param request Render request object instance
     * @param response Render response object isntance
     *
     * @throws PortletException
     * @throws IOException
     */
    @Override
    public void doHelp(RenderRequest request, RenderResponse response)
    throws PortletException,IOException {
        _log.info("Calling doHelp ...");
        response.setContentType("text/html");
        request.setAttribute("portletVersion",appPreferences.getPortletVersion());
        PortletRequestDispatcher dispatcher=getPortletContext().getRequestDispatcher("/help.jsp");
        dispatcher.include(request, response);
    } // doHelp

    //----------------------------
    // Portlet Standard Methods
    //----------------------------

    /**
     * This method is used to retrieve from the Application preferences the
     * GridEngine' GridOperations identifier associated to this application
     * Such index is automatically created when registering the application
     * with the couple (portalName,applicationDesc)
     * The portal name is automatically extracted from the Application
     * The portal description is defined in the default parameters (portlet.xml)
     * This method can be called by processAction or doViewe evakuating one of the
     * corresponding actionRequest or renderRequest object instances
     *
     * @param actionRequest an ActionRequest instance or,
     * @param renderRequest a RenderRequest instance
     * @return The GridOperationId associated to this application or -1 if the application is not yet registered
     *
     * @see AppPreferences
     *
    private String getPrefGridOperationId(ActionRequest actionRequest
                                        , RenderRequest renderRequest) {
        PortletPreferences portletPreferences;
        String prefOperationId="";
        if(null != actionRequest) {
            portletPreferences= actionRequest.getPreferences();
            prefOperationId   = portletPreferences.getValue("pref_gridOperationId","-1");
        }
        if(null != renderRequest) {
            portletPreferences= renderRequest.getPreferences();
            prefOperationId   = portletPreferences.getValue("pref_gridOperationId","-1");
        }
        return prefOperationId;
    }
    */

    /**
     * This method Uses the AppPreference object settings to store Application preferences
     *
     * @param request ActinRequest instance (called by the processAction)
     *
     * @throws PortletException
     * @throws IOException
     */
    void storePreferences(ActionRequest request)
    throws PortletException, IOException{
        _log.info("Calling storePreferences ...");
        // Stored preference content
        String storedPrefs="Stored preferences:"
                       +LS+"-------------------"
                       +LS;
        // The code below stores all the portlet preference values
        PortletPreferences prefs = request.getPreferences();
        if(prefs!=null) {
            String logLevel              =appPreferences.getLogLevel              ();
            String gridOperationId       =appPreferences.getGridOperationId       ();
            int    numInfrastructures    =appPreferences.getNumInfrastructures    ();
            int    currPaneInfrastructure=appPreferences.getCurrPaneInfrastructure();
            String gridOperationDesc     =appPreferences.getGridOperationDesc     ();
            prefs.setValue("pref_logLevel"          , ""+logLevel              );
            prefs.setValue("pref_gridOperationId"   , ""+gridOperationId       );
            prefs.setValue("pref_gridOperationDesc" , ""+gridOperationDesc     );
            prefs.setValue("pref_numInfrastructures", ""+numInfrastructures    );
            prefs.setValue("pref_currInfrastructure", ""+currPaneInfrastructure);
            storedPrefs+="pref_logLevel           : '"+logLevel              +"'"
                     +LS+"pref_gridOperationId    : '"+gridOperationId       +"'"
                     +LS+"pref_gridOperationDesc  : '"+gridOperationDesc     +"'"
                     +LS+"pref_numInfrastructures : '"+numInfrastructures    +"'"
                     +LS+"pref_currInfrastructure : '"+currPaneInfrastructure+"'"
                     +LS;
            // For each preference infrastructure
            for(int i=0; i<numInfrastructures; i++) {
                int j=i+1;
                storedPrefs=LS+"Infrastructure #"+j
                           +LS+"--------------------"
                           +LS;
                String enableInfrastructure =appPreferences.getEnableInfrastructure (i);
                String nameInfrastructure   =appPreferences.getNameInfrastructure   (i);
                String acronymInfrastructure=appPreferences.getAcronymInfrastructure(i);
                String bdiiHost             =appPreferences.getBdiiHost             (i);
                String wmsHost              =appPreferences.getWmsHosts             (i);
                String pxServerHost         =appPreferences.getPxServerHost         (i);
                String pxServerPort         =appPreferences.getPxServerPort         (i);
                String pxServerSecure       =appPreferences.getPxServerSecure       (i);
                String pxRobotId            =appPreferences.getPxRobotId            (i);
                String pxRobotVO            =appPreferences.getPxRobotVO            (i);
                String pxRobotRole          =appPreferences.getPxRobotRole          (i);
                String pxRobotRenewalFlag   =appPreferences.getPxRobotRenewalFlag   (i);
                String pxUserProxy          =appPreferences.getPxUserProxy          (i);
                String softwareTags         =appPreferences.getSoftwareTags         (i);
                // Set preference values
                prefs.setValue("pref_"+j+"_enableInfrastructure" ,enableInfrastructure );
                prefs.setValue("pref_"+j+"_nameInfrastructure"   ,nameInfrastructure   );
                prefs.setValue("pref_"+j+"_acronymInfrastructure",acronymInfrastructure);
                prefs.setValue("pref_"+j+"_bdiiHost"             ,bdiiHost             );
                prefs.setValue("pref_"+j+"_wmsHosts"             ,wmsHost              );
                prefs.setValue("pref_"+j+"_pxServerHost"         ,pxServerHost         );
                prefs.setValue("pref_"+j+"_pxServerPort"         ,pxServerPort         );
                prefs.setValue("pref_"+j+"_pxServerSecure"       ,pxServerSecure       );
                prefs.setValue("pref_"+j+"_pxRobotId"            ,pxRobotId            );
                prefs.setValue("pref_"+j+"_pxRobotVO"            ,pxRobotVO            );
                prefs.setValue("pref_"+j+"_pxRobotRole"          ,pxRobotRole          );
                prefs.setValue("pref_"+j+"_pxRobotRenewalFlag"   ,pxRobotRenewalFlag   );
                prefs.setValue("pref_"+j+"_pxUserProxy"          ,pxUserProxy          );
                prefs.setValue("pref_"+j+"_softwareTags"         ,softwareTags         );
                // Dumps the infrastructure preferences
                storedPrefs+=  "  pref_"+j+"_enableInfrastructure : '"+enableInfrastructure +"'"
                           +LS+"  pref_"+j+"_nameInfrastructure   : '"+nameInfrastructure   +"'"
                           +LS+"  pref_"+j+"_acronymInfrastructure: '"+acronymInfrastructure+"'"
                           +LS+"  pref_"+j+"_bdiiHost             : '"+bdiiHost             +"'"
                           +LS+"  pref_"+j+"_wmsHosts             : '"+wmsHost              +"'"
                           +LS+"  pref_"+j+"_pxServerHost         : '"+pxServerHost         +"'"
                           +LS+"  pref_"+j+"_pxServerPort         : '"+pxServerPort         +"'"
                           +LS+"  pref_"+j+"_pxServerSecure       : '"+pxServerSecure       +"'"
                           +LS+"  pref_"+j+"_pxRobotId            : '"+pxRobotId            +"'"
                           +LS+"  pref_"+j+"_pxRobotVO            : '"+pxRobotVO            +"'"
                           +LS+"  pref_"+j+"_pxRobotRole          : '"+pxRobotRole          +"'"
                           +LS+"  pref_"+j+"_pxRobotRenewalFlag   : '"+pxRobotRenewalFlag   +"'"
                           +LS+"  pref_"+j+"_pxUserProxy          : '"+pxUserProxy          +"'"
                           +LS+"  pref_"+j+"_softwareTags         : '"+softwareTags         +"'"
                           +LS;
            } // for each preference infrastructure
            String jobRequirements=appInitPreferences.getJobRequirements();
            String pilotScript    =appInitPreferences.getPilotScript    ();
            prefs.setValue("pref_jobRequirements", jobRequirements);
            prefs.setValue("pref_pilotScript"    , pilotScript    );
//LIC
            String sciGwyUserTrackingDB_Hostname=appPreferences.getSciGwyUserTrackingDB_Hostname();
            String sciGwyUserTrackingDB_Username=appPreferences.getSciGwyUserTrackingDB_Username();
            String sciGwyUserTrackingDB_Password=appPreferences.getSciGwyUserTrackingDB_Password();
            String sciGwyUserTrackingDB_Database=appPreferences.getSciGwyUserTrackingDB_Database();
            prefs.setValue("pref_sciGwyUserTrackingDB_Hostname",sciGwyUserTrackingDB_Hostname);
            prefs.setValue("pref_sciGwyUserTrackingDB_Username",sciGwyUserTrackingDB_Username);
            prefs.setValue("pref_sciGwyUserTrackingDB_Password",sciGwyUserTrackingDB_Password);
            prefs.setValue("pref_sciGwyUserTrackingDB_Database",sciGwyUserTrackingDB_Database);

            storedPrefs+=  "pref_jobRequirements              : '"+jobRequirements              +"'"
                       +LS+"pref_pilotScript                  : '"+pilotScript                  +"'"
                       +LS+"pref_sciGwyUserTrackingDB_Hostname: '"+sciGwyUserTrackingDB_Hostname+"'"
                       +LS+"pref_sciGwyUserTrackingDB_Username: '"+sciGwyUserTrackingDB_Username+"'"
                       +LS+"pref_sciGwyUserTrackingDB_Password: '"+sciGwyUserTrackingDB_Password+"'"
                       +LS+"pref_sciGwyUserTrackingDB_Database: '"+sciGwyUserTrackingDB_Database+"'"
                       +LS;
            // Store preferences
            prefs.store();
        } // pref !=null

        // Show saved preferences
        _log.info("Stored preferences"
              +LS+"------------------"
              +storedPrefs
              +LS);

    } // storePreferences

    /**
     * This method fills the appPreferences values retrieving them frorm the
     * portlet preference object.
     * This method can be called by both processAction or doView methods
     * in case no preference values are yet defined the default settings loaded
     * by the init method will be used
     *
     * @param actionRequest an ActionRequest instance or,
     * @param renderRequest a RenderRequest instance
     *
     */
    private void getPreferences( ActionRequest actionRequest
                                ,RenderRequest renderRequest) {
        _log.info("Calling: getPreferences ...");
        PortletPreferences prefs=null;

        if(null!=actionRequest)
            prefs = actionRequest.getPreferences();
        else if(null != renderRequest)
            prefs = renderRequest.getPreferences();
        else _log.warn("Both render request and action request are null");

        if (null != prefs) {
            appPreferences.updateValue(          "logLevel",""+prefs.getValue(          "pref_logLevel",   appInitPreferences.getLogLevel          ()));
            appPreferences.updateValue(   "gridOperationId",""+prefs.getValue(   "pref_gridOperationId",   appInitPreferences.getGridOperationId   ()));
            appPreferences.updateValue( "gridOperationDesc",""+prefs.getValue( "pref_gridOperationDesc",   appInitPreferences.getGridOperationDesc ()));
            appPreferences.updateValue("numInfrastructures",""+prefs.getValue("pref_numInfrastructures",""+appInitPreferences.getNumInfrastructures()));

            // Now retrieves the infrastructures information
            int numInfras=appPreferences.getNumInfrastructures();
            _log.info("getpref: num infra="+numInfras);

            // For each infrastructure ...
            // The preference name is indexed with the infrastructure number: 1,2,...
            String infrastructuresInfrormations="";
            for(int i=0; i<numInfras; i++) {
                int j=i+1;
                int k=appInitPreferences.getNumInfrastructures();
                appPreferences.updateInfrastructureValue(i, "enableInfrastructure",""+prefs.getValue("pref_"+j+ "_enableInfrastructure",(i<k)?appInitPreferences.getEnableInfrastructure (i):""));
                appPreferences.updateInfrastructureValue(i,   "nameInfrastructure",""+prefs.getValue("pref_"+j+   "_nameInfrastructure",(i<k)?appInitPreferences.getNameInfrastructure   (i):""));
                appPreferences.updateInfrastructureValue(i,"acronymInfrastructure",""+prefs.getValue("pref_"+j+"_acronymInfrastructure",(i<k)?appInitPreferences.getAcronymInfrastructure(i):""));
                appPreferences.updateInfrastructureValue(i,             "bdiiHost",""+prefs.getValue("pref_"+j+             "_bdiiHost",(i<k)?appInitPreferences.getBdiiHost             (i):""));
                appPreferences.updateInfrastructureValue(i,             "wmsHosts",""+prefs.getValue("pref_"+j+             "_wmsHosts",(i<k)?appInitPreferences.getWmsHosts             (i):""));
                appPreferences.updateInfrastructureValue(i,         "pxServerHost",""+prefs.getValue("pref_"+j+         "_pxServerHost",(i<k)?appInitPreferences.getPxServerHost         (i):""));
                appPreferences.updateInfrastructureValue(i,         "pxServerPort",""+prefs.getValue("pref_"+j+         "_pxServerPort",(i<k)?appInitPreferences.getPxServerPort         (i):""));
                appPreferences.updateInfrastructureValue(i,       "pxServerSecure",""+prefs.getValue("pref_"+j+       "_pxServerSecure",(i<k)?appInitPreferences.getPxServerSecure       (i):""));
                appPreferences.updateInfrastructureValue(i,            "pxRobotId",""+prefs.getValue("pref_"+j+            "_pxRobotId",(i<k)?appInitPreferences.getPxRobotId            (i):""));
                appPreferences.updateInfrastructureValue(i,            "pxRobotVO",""+prefs.getValue("pref_"+j+            "_pxRobotVO",(i<k)?appInitPreferences.getPxRobotVO            (i):""));
                appPreferences.updateInfrastructureValue(i,          "pxRobotRole",""+prefs.getValue("pref_"+j+          "_pxRobotRole",(i<k)?appInitPreferences.getPxRobotRole          (i):""));
                appPreferences.updateInfrastructureValue(i,   "pxRobotRenewalFlag",""+prefs.getValue("pref_"+j+   "_pxRobotRenewalFlag",(i<k)?appInitPreferences.getPxRobotRenewalFlag   (i):""));
                appPreferences.updateInfrastructureValue(i,          "pxUserProxy",""+prefs.getValue("pref_"+j+          "_pxUserProxy",(i<k)?appInitPreferences.getPxUserProxy          (i):""));
                appPreferences.updateInfrastructureValue(i,         "softwareTags",""+prefs.getValue("pref_"+j+         "_softwareTags",(i<k)?appInitPreferences.getSoftwareTags         (i):""));
                _log.info("dump: "
                       +LS+appPreferences.dumpInfrastructure(i));
            } // for each Infrastructure

            appPreferences.updateValue("jobRequirements",""+prefs.getValue("pref_jobRequirements",appInitPreferences.getJobRequirements()));
            appPreferences.updateValue(    "pilotScript",""+prefs.getValue(    "pref_pilotScript",appInitPreferences.getPilotScript    ()));
//LIC
            appPreferences.updateValue("sciGwyUserTrackingDB_Hostname",
                                                         ""+prefs.getValue("pref_sciGwyUserTrackingDB_Hostname",
                                                                           appInitPreferences.getSciGwyUserTrackingDB_Hostname()));
            appPreferences.updateValue("sciGwyUserTrackingDB_Username",
                                                         ""+prefs.getValue("pref_sciGwyUserTrackingDB_Username",
                                                                           appInitPreferences.getSciGwyUserTrackingDB_Username()));
            appPreferences.updateValue("sciGwyUserTrackingDB_Password",
                                                         ""+prefs.getValue("pref_sciGwyUserTrackingDB_Password",
                                                                           appInitPreferences.getSciGwyUserTrackingDB_Password()));
            appPreferences.updateValue("sciGwyUserTrackingDB_Database",
                                                         ""+prefs.getValue("pref_sciGwyUserTrackingDB_Database",
                                                                           appInitPreferences.getSciGwyUserTrackingDB_Database()));
            // Assigns the log level
            _log.setLogLevel(appPreferences.getLogLevel());

            // Show preference values into log
            _log.info(appPreferences.dump());
        } // if
    } // getPreferences


    /**
     * This method takes as input a filename and will transfer its content inside a String variable
     *
     * @param file A complete path to a given file
     * @return File content into a String
     * @throws IOException
     */
    private String updateString(String file) throws IOException {
        String line;
        StringBuilder stringBuilder = new StringBuilder();
        BufferedReader reader = new BufferedReader( new FileReader (file));
        while((line = reader.readLine()) != null ) {
            stringBuilder.append(line);
            stringBuilder.append(LS);
        }
        return stringBuilder.toString();
    }

    /**
     * This method will transfer the content of a given String into a given filename
     *
     * @param fileName    A complete path to a file to write
     * @param fileContent The string content of the file to write
     * @throws IOException
     */
    private void storeString(String fileName,String fileContent) throws IOException {
        BufferedWriter writer = new BufferedWriter(new FileWriter(fileName));
        writer.write(fileContent);
        writer.close();
    }

    /**
     * This enumerated type contains all JSP input items to be managed
     * by the getInputForm method
     *
     * @see getInputForm
     */
    private enum inputControlsIds {
        file_inputFile    // Input file textarea
       ,inputFile         // Input file input file
       ,JobIdentifier     // User defined Job identifier
    };
    /**
     * This method manages the user input fields managing two cases
     * distinguished by the type of the input <form ... statement
     * The use of upload file controls needs the use of "multipart/form-data"
     * while the else condition of the isMultipartContent check manages the
     * standard input case. The multipart content needs a manual processing of
     * all <form items
     * All form' input items are identified by the 'name' input property
     * inside the jsp file
     *
     * @param request   ActionRequest instance (processAction)
     * @param appInput  AppInput instance storing the jobSubmission data
     */
    void getInputForm(ActionRequest request,AppInput appInput) {
    if (PortletFileUpload.isMultipartContent(request))
        try {
            FileItemFactory factory = new DiskFileItemFactory();
            PortletFileUpload upload = new PortletFileUpload( factory );
            List items = upload.parseRequest(request);
            File repositoryPath = new File("/tmp");
            DiskFileItemFactory diskFileItemFactory = new DiskFileItemFactory();
            diskFileItemFactory.setRepository(repositoryPath);
            Iterator iter = items.iterator();
            String logstring="";
            while (iter.hasNext()) {
                FileItem item = (FileItem)iter.next();
                String   fieldName  =item.getFieldName();
                String   fileName   =item.getName();
                String   contentType=item.getContentType();
                boolean  isInMemory =item.isInMemory();
                long     sizeInBytes=item.getSize();
                // Prepare a log string with field list
                logstring+=LS+"field name: '"+fieldName+"' - '"+item.getString()+"'";
                switch(inputControlsIds.valueOf(fieldName)) {
                    case file_inputFile:
                        appInput.inputFileName=item.getString();
                        processInputFile(item,appInput);
                    break;
                    case inputFile:
                        appInput.inputFileText=item.getString();
                    break;
                    case JobIdentifier:
                        appInput.jobIdentifier=item.getString();
                    break;
                    default:
                        _log.warn("Unhandled input field: '"+fieldName+"' - '"+item.getString()+"'");
                } // switch fieldName
            } // while iter.hasNext()
            _log.info(
                   LS+"Reporting"
                  +LS+"---------"
                  +LS+logstring
                  +LS);
        } // try
        catch (Exception e) {
            _log.info("Caught exception while processing files to upload: '"+e.toString()+"'");
        }
        // The input form do not use the "multipart/form-data"
        else  {
            // Retrieve from the input form the given application values
            appInput.inputFileName=(String)request.getParameter("file_inputFile");
            appInput.inputFileText=(String)request.getParameter("inputFile");
            appInput.jobIdentifier=(String)request.getParameter("JobIdentifier");
        } // ! isMultipartContent

        // Show into the log the taken inputs
        _log.info(
               LS+"Taken input parameters:"
              +LS+"-----------------------"
              +LS+"inputFileName: '"+appInput.inputFileName+"'"
              +LS+"inputFileText: '"+appInput.inputFileText+"'"
              +LS+"jobIdentifier: '"+appInput.jobIdentifier+"'"
              +LS);
    } // getInputForm

    /**
     * This method is called when the user specifies a input file to upload
     * The file will be saved first into /tmp directory and then its content
     * stored into the corresponding String variable
     * Before to submit the job the String value will be stored in the
     * proper job inputSandbox file
     *
     * @param item
     * @param appInput  AppInput instance storing the jobSubmission data
     */
    void processInputFile(FileItem item,AppInput appInput) {
        // Determin the filename
        String fileName = item.getName();
        if(!fileName.equals("")) {
            // Determine the fieldName
            String fieldName = item.getFieldName();

            // Create a filename for the uploaded file
            String theNewFileName = "/tmp/"
                                   +appInput.timestamp
                                   +"_"
                                   +appInput.username
                                   +"_"
                                   +fileName;
            File uploadedFile = new File(theNewFileName);
            _log.info("Uploading file: '"+fileName+"' into '"+theNewFileName+"'");
            try {
                item.write(uploadedFile);
            }
            catch (Exception e) {
                _log.error("Caught exception while uploading file: 'file_inputFile'");
            }
            // File content has to be inserted into a String variables:
            //   inputFileName -> inputFileText
            try {
                if(fieldName.equals("file_inputFile"))
                 appInput.inputFileText=updateString(theNewFileName);
                 // Other params can be added as below ...
                 //else if(fieldName.equals("..."))
                 //   ...=updateString(theNewFileName);
            else { // Never happens
                 }
            }
            catch (Exception e) {
                _log.error("Caught exception while processing strings: '"+e.toString()+"'");
            }
        } // if
    } // processInputFile

    /**
     * Before to submit the job this method creates the inputSandbox files
     * starting from users' input fields (textareas or uploaded files)
     *
     * @param appInput  AppInput instance storing the jobSubmission data
     */
    void updateFiles(AppInput appInput) {
        // First of all remomve all possible ^Ms from Strings
        appInput.inputFileText=appInput.inputFileText.replaceAll("\r","");
        // Now save string content into files
        // This must be done for each input sandbox file
        try {
            appInput.inputSandbox_inputFile="/tmp/"
                                                +appInput.timestamp
                                                +"_"
                                                +appInput.username
                                                +"_input_file.txt"
                                                ;
            FileWriter fwInput=new FileWriter(appInput.inputSandbox_inputFile);
            BufferedWriter bwInput = new BufferedWriter(fwInput);
            bwInput.write(appInput.inputFileText);
            bwInput.close();
        }
        catch (Exception e) {
            _log.error("Caught exception while creating inputSandbox files");
        }
    } // updateFiles

    /**
     * -- WARNING -------------------------------------------------------
     * (DEPRECATED) This method will be left only for some future commits
     * ------------------------------------------------------------------
     * This method sends the job into the distributed infrastructure using
     * the GridEngine methods
     *
     * @param appInput  AppInput instance storing the jobSubmission data
     */
    void __submitJob(AppInput appInput) {
        // GridEngine' MultiInfrastructure job submission object
        MultiInfrastructureJobSubmission miJobSubmission=null;

        //
        // Initialize the GridEngine Multi Infrastructure Job Submission object
        //
        //  GridEngine uses two different kind of constructors. The constructor
        //  taking void type as argument is used for production environments, while
        //  the constructor taking SciGwyUserTrackingDB parameters is normally used
        //  for development purposes. In order to switch-on the production constructor
        //  just set to empty strings the following portlet init parameters:
        //      sciGwyUserTrackingDB_Hostname
        //      sciGwyUserTrackingDB_Username
        //      sciGwyUserTrackingDB_Password
        //      sciGwyUserTrackingDB_Database
        //
        if(null !=  appPreferences.getSciGwyUserTrackingDB_Hostname()
                && !appPreferences.getSciGwyUserTrackingDB_Hostname().equals("")
        && null !=  appPreferences.getSciGwyUserTrackingDB_Username()
                && !appPreferences.getSciGwyUserTrackingDB_Username().equals("")
        && null !=  appPreferences.getSciGwyUserTrackingDB_Password()
                && !appPreferences.getSciGwyUserTrackingDB_Password().equals("")
        && null !=  appPreferences.getSciGwyUserTrackingDB_Database()
                && !appPreferences.getSciGwyUserTrackingDB_Database().equals("")
        ) {
           String arg1="jdbc:mysql://" + appPreferences.getSciGwyUserTrackingDB_Hostname() +
                                   "/" + appPreferences.getSciGwyUserTrackingDB_Database();
           String arg2=appPreferences.getSciGwyUserTrackingDB_Username();
           String arg3=appPreferences.getSciGwyUserTrackingDB_Password();
            miJobSubmission = new MultiInfrastructureJobSubmission(arg1,arg2,arg3);
            _log.info("MultiInfrastructureJobSubmission [DEVEL]\n"
                     +LS+"    Arg1: '" +  arg1 + "'"
                     +LS+"    Arg2: '" +  arg2 + "'"
                     +LS+"    Arg3: '" +  arg3 + "'"
                     );
        }
        else {
            miJobSubmission = new MultiInfrastructureJobSubmission();
            _log.info("MultiInfrastructureJobSubmission [PROD]");
        }

        // Assigns all enabled infrastructures
        InfrastructureInfo[] infrastructuresInfo=appPreferences.getEnabledInfrastructures();
        for(int i=0; i<infrastructuresInfo.length; i++) {
            _log.info("Adding infrastructure #"+(i+1)
                     +" - Name: '"+infrastructuresInfo[i].getName()+"'"+LS);
            miJobSubmission.addInfrastructure(infrastructuresInfo[i]);
        }

        // Check the enabled infrastructures
        if(infrastructuresInfo.length > 0) {
            // Application Id
            int applicationId=Integer.parseInt(appPreferences.getGridOperationId());

            // Grid Engine' UserTraking needs the portal IP address
            String   portalIPAddress="";
            try {
                InetAddress addr = InetAddress.getLocalHost();
                byte[] ipAddr=addr.getAddress();
                portalIPAddress= ""+(short)(ipAddr[0]&0xff)
                               +":"+(short)(ipAddr[1]&0xff)
                               +":"+(short)(ipAddr[2]&0xff)
                               +":"+(short)(ipAddr[3]&0xff);
            }
            catch(Exception e) {
                _log.error("Unable to get the portal IP address");
            }

            // Job details
            String executable="/bin/sh";                       // Application executable
            String arguments =appPreferences.getPilotScript(); // executable' arguments
            String outputPath="/tmp/";                         // Output Path
            String outputFile="code-rade-Output.txt";           // Distributed application standard output
            String errorFile ="code-rade-Error.txt";            // Distrubuted application standard error
            String appFile   ="code-rade-Files.tar.gz";         // Hostname output files (created by the pilot script)

            // InputSandbox (string with comma separated list of file names)
            String inputSandbox=appServerPath+"WEB-INF/job/"        //
                               +appPreferences.getPilotScript()     // pilot script
                               +","+appInput.inputSandbox_inputFile // input file
                               ;
            // OutputSandbox (string with comma separated list of file names)
            String outputSandbox=appFile;                                     // Output file

            // Take care of job requirements
            // More requirements can be specified in the preference value 'jobRequirements'
            // separating each requirement by the ';' character
            String jdlRequirements[] = appPreferences.getJobRequirements().split(";");
            int numRequirements=0;
            for(int i=0; i<jdlRequirements.length; i++) {
                if(!jdlRequirements[i].equals("")) {
                  jdlRequirements[numRequirements] = "JDLRequirements=("+jdlRequirements[i]+")";
                  numRequirements++;
                  _log.info("Requirement["+i+"]='"+jdlRequirements[i]+"'");
                }
            } // for each jobRequirement

            // Other job initialization settings
            miJobSubmission.setExecutable (   executable); // Specify the executeable
            miJobSubmission.setArguments  (    arguments); // Specify the application' arguments
            miJobSubmission.setOutputPath (   outputPath); // Specify the output directory
            miJobSubmission.setOutputFiles(outputSandbox); // Setup output files (OutputSandbox)
            miJobSubmission.setJobOutput  (   outputFile); // Specify the std-outputr file
            miJobSubmission.setJobError   (    errorFile); // Specify the std-error file
            if(   null != inputSandbox                     // Setup input files (InputSandbox) avoiding empty inputSandboxes
               && inputSandbox.length() > 0)
                miJobSubmission.setInputFiles(inputSandbox);
            if(numRequirements>0)                          // Setup the JDL requirements
                miJobSubmission.setJDLRequirements(jdlRequirements);

            // Submit Job
            miJobSubmission.submitJobAsync(appInput.username, portalIPAddress, applicationId, appInput.jobIdentifier);

            // Show log
            // View jobSubmission details in the log
            _log.info(
               LS+"JobSent"
              +LS+"-------"
              +LS+"Portal address: '"+portalIPAddress+"'"
              +LS+"Executable    : '"+executable     +"'"
              +LS+"Arguments     : '"+arguments      +"'"
              +LS+"Output path   : '"+outputPath     +"'"
              +LS+"Output sandbox: '"+outputSandbox  +"'"
              +LS+"Ouput file    : '"+outputFile     +"'"
              +LS+"Error file    : '"+errorFile      +"'"
              +LS+"Input sandbox : '"+inputSandbox   +"'"
              +LS); // _log.info

        } // numInfra > 0
        else {
            _log.warn(
                   LS+"There are no enough enabled infrastructures!"
                  +LS+"It is impossible to send any job"
                  +LS+"Configure the application preferences in order to setup"
                  +LS+"or enable at least one infrastructure."
                  +LS);
        } // numInfra == 0
   } // __submitJob

   /**
     * -- WARNING -------------------------------------------------------
     * (DEPRECATED) This method will be left only for some future commits
     * ------------------------------------------------------------------
     * This method sends the job into the distributed infrastructure using
     * the GridEngine methods
     *
     * @param appInput  AppInput instance storing the jobSubmission data
     */
   void submitJob(AppInput appInput) {

        // Job details
        String executable="/bin/sh";                       // Application executable
        String arguments =appPreferences.getPilotScript(); // executable' arguments
        String outputPath="/tmp/";                         // Output Path
        String outputFile="code-rade-Output.txt";           // Distributed application standard output
        String errorFile ="code-rade-Error.txt";            // Distrubuted application standard error
        String appFile   ="code-rade-Files.tar.gz";         // Hostname output files (created by the pilot script)

        // InputSandbox (string with comma separated list of file names)
        String inputSandbox=appServerPath+"WEB-INF/job/"    //
                           +appPreferences.getPilotScript() // pilot script
                       +","+appInput.inputSandbox_inputFile // input file
                           ;
        // OutputSandbox (string with comma separated list of file names)
        String outputSandbox=appFile;                       // Output file

        // Take care of job requirements
        // More requirements can be specified in the preference value 'jobRequirements'
        // separating each requirement by the ';' character
        // The loop prepares a string array with GridEngine/JSAGA compliant requirements
        String jdlRequirements[] = appPreferences.getJobRequirements().split(";");
        int numRequirements=0;
        for(int i=0; i<jdlRequirements.length; i++) {
            if(!jdlRequirements[i].equals("")) {
              jdlRequirements[numRequirements] = "JDLRequirements=("+jdlRequirements[i]+")";
              numRequirements++;
              _log.info("Requirement["+i+"]='"+jdlRequirements[i]+"'");
            }
        } // for each jobRequirement

        // Prepare the GridEngine job description
        GEJobDescription jobDesc = new GEJobDescription();
        jobDesc.setExecutable (   executable); // Specify the executeable
        jobDesc.setArguments  (    arguments); // Specify the application' arguments
        jobDesc.setOutputPath (   outputPath); // Specify the output directory
        jobDesc.setOutput     (   outputFile); // Specify the std-output file
        jobDesc.setError      (    errorFile); // Specify the std-error file
        jobDesc.setOutputFiles(outputSandbox); // Setup output files (OutputSandbox) (*)
        jobDesc.setInputFiles ( inputSandbox); // Setut input files (InputSandbox)

        // GridEngine' MultiInfrastructure job submission object
        MultiInfrastructureJobSubmission miJobSubmission=null;

        //
        // Initialize the GridEngine Multi Infrastructure Job Submission object
        //
        //  GridEngine uses two different kind of constructors. The constructor
        //  taking no database arguments is used for production environments, while
        //  the constructor taking SciGwyUserTrackingDB parameters is normally used
        //  for development purposes. In order to switch-on the production constructor
        //  just set to empty strings the following portlet init parameters or form
        //  the portlet preferences:
        //      sciGwyUserTrackingDB_Hostname
        //      sciGwyUserTrackingDB_Username
        //      sciGwyUserTrackingDB_Password
        //      sciGwyUserTrackingDB_Database
        //
        if(null !=  appPreferences.getSciGwyUserTrackingDB_Hostname()
                && !appPreferences.getSciGwyUserTrackingDB_Hostname().equals("")
        && null !=  appPreferences.getSciGwyUserTrackingDB_Username()
                && !appPreferences.getSciGwyUserTrackingDB_Username().equals("")
        && null !=  appPreferences.getSciGwyUserTrackingDB_Password()
                && !appPreferences.getSciGwyUserTrackingDB_Password().equals("")
        && null !=  appPreferences.getSciGwyUserTrackingDB_Database()
                && !appPreferences.getSciGwyUserTrackingDB_Database().equals("")
        ) {
           String DBNM="jdbc:mysql://" + appPreferences.getSciGwyUserTrackingDB_Hostname() +
                                   "/" + appPreferences.getSciGwyUserTrackingDB_Database();
           String DBUS=appPreferences.getSciGwyUserTrackingDB_Username();
           String DBPW=appPreferences.getSciGwyUserTrackingDB_Password();
           miJobSubmission = new MultiInfrastructureJobSubmission(DBNM,DBUS,DBPW,jobDesc);
            _log.info("MultiInfrastructureJobSubmission [DEVEL]\n"
                     +LS+"    DBNM: '" +  DBNM + "'"
                     +LS+"    DBUS: '" +  DBUS + "'"
                     +LS+"    DBPW: '" +  DBPW + "'"
                     );
        }
        else {
            miJobSubmission = new MultiInfrastructureJobSubmission(jobDesc);
            _log.info("MultiInfrastructureJobSubmission [PROD]");
        }
        
        // Assigns now all enabled infrastructures
        InfrastructureInfo[] infrastructuresInfo=appPreferences.getEnabledInfrastructures();
        for(int i=0; i<infrastructuresInfo.length; i++) {
            _log.info("Adding infrastructure #"+(i+1)
                     +" - Name: '"+infrastructuresInfo[i].getName()+"'"+LS);
            miJobSubmission.addInfrastructure(infrastructuresInfo[i]);
        }
        
        // Check the enabled infrastructures
        if(infrastructuresInfo.length > 0) {
            
            // GridOperations' Application Id
            int applicationId=Integer.parseInt(appPreferences.getGridOperationId());
            
            // Grid Engine' UserTraking needs the portal IP address
            String   portalIPAddress="";
            try {
                InetAddress addr = InetAddress.getLocalHost();
                byte[] ipAddr=addr.getAddress();
                portalIPAddress= ""+(short)(ipAddr[0]&0xff)
                               +":"+(short)(ipAddr[1]&0xff)
                               +":"+(short)(ipAddr[2]&0xff)
                               +":"+(short)(ipAddr[3]&0xff);
            }
            catch(Exception e) {
                _log.error("Unable to get the portal IP address");
            }
            
            // Setup job requirements
            if(numRequirements>0)                      
                miJobSubmission.setJDLRequirements(jdlRequirements);
            
            // Ready now to submit the Job
            miJobSubmission.submitJobAsync( appInput.username
                                           ,portalIPAddress
                                           ,applicationId
                                           ,appInput.jobIdentifier
                                          );
            
            // Show log
            // View jobSubmission details in the log
            _log.info(
               LS+"JobSent"
              +LS+"-------"
              +LS+"Portal address: '"+portalIPAddress+"'"
              +LS+"Executable    : '"+executable     +"'"
              +LS+"Arguments     : '"+arguments      +"'"
              +LS+"Output path   : '"+outputPath     +"'"
              +LS+"Output sandbox: '"+outputSandbox  +"'"
              +LS+"Ouput file    : '"+outputFile     +"'"
              +LS+"Error file    : '"+errorFile      +"'"
              +LS+"Input sandbox : '"+inputSandbox   +"'"
              +LS); // _log.info
        } // numInfra > 0
        else {
            _log.warn(
                   LS+"There are no enough enabled infrastructures!"
                  +LS+"It is impossible to send any job"
                  +LS+"Configure the application preferences in order to setup"
                  +LS+"or enable at least one infrastructure."
                  +LS);
        } // numInfra == 0
   } // submitJob
} // code_rade_portletcic

