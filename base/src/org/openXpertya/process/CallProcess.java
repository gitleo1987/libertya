package org.openXpertya.process;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.HashMap;

import org.openXpertya.OpenXpertya;
import org.openXpertya.apps.ProcessParameter;
import org.openXpertya.model.MProcess;
import org.openXpertya.process.ProcessInfo;
import org.openXpertya.process.ProcessInfoParameter;
import org.openXpertya.process.ProcessInfoUtil;
import org.openXpertya.util.DB;
import org.openXpertya.util.DisplayType;
import org.openXpertya.util.Env;
import org.openXpertya.util.Trx;

public class CallProcess {
	
	/** Parametros a setear específicos del AD_Process de postinstall a ejecutar */
	protected static HashMap<String, String> additionalParams = new HashMap<String, String>();

	
	/**
	 * Invocación de procesos Libertya genérica 
	 * Parámetro 1: Clave de búsqueda del Proceso
	 * Parámtros opcionales (dinámico, indicando NombreDeParametro=ValorDeParametro, separados por espacio cada uno)
	 * 		- CTX_Client_ID: si se indica valor para este parámetro, lo usa como ID de la Compañía, sino utiliza 1010016
	 *  	- CTX_Org_ID: si se indica valor para este parámtro, se usa como ID Organizaciòn, sino utiliza 0
	 *  	- Resto de los parámetros, deben ser los del proceso, utilizando como clave el "Nombre de la Columna en DB"
	 * @param args
	 */
	public static void main(String[] args) {
		
		/**********************  PREPARACION DEL ENTORNO  ********************************/
		
		// Validación de parámetros obligatorios
		if (args.length < 1) {
			System.err.println("Debe especificar al menos 1 parámetro: ");
			System.err.println("\t	1) Clave de Búsqueda del proceso a invocar ");
			System.err.println("\t	2) en adelante (Opcionales) Parametros especificos del proceso a ejecutar, indicando NombreDeParametro=ValorDeParametro, separados por espacio cada uno. Puede indicarse CTX_Client_ID y CTX_Org_ID específicos, por defecto se usa 1010016 y 0 respectivamente");
			System.exit(1);
		}
			
	  	// OXP_HOME seteada?
	  	String oxpHomeDir = System.getenv("OXP_HOME"); 
	  	if (oxpHomeDir == null) { 
	  		System.err.println("ERROR: La variable de entorno OXP_HOME no está seteada ");
	  		System.exit(1);
	  	}
	  	
	  	// Cargar el entorno basico
	  	System.setProperty("OXP_HOME", oxpHomeDir);
	  	if (!OpenXpertya.startupEnvironment( false )) {
	  		System.err.println("ERROR: Error al iniciar la configuracion... Postgres esta levantado?");
	  		System.exit(1);
	  	}
	  	
	  	//Argumento 1 - Nombre del proceso
	  	String processName = args[0];
	  	
	  	System.out.println("Ejecutando proceso " + processName + "...");
	  	
	  	//Levanto el resto de los argumentos
	  	int i = 0;
	  	System.out.println("Argumentos recibidos desde consola: ");
	  	for (String arg : args) {
			if (++i>=2) {
				String[] paramKV = arg.split("="); 
				additionalParams.put(paramKV[0], paramKV[1]);
				
				System.out.println(paramKV[0] + "=" + paramKV[1]);
			}
		}
	  	
	  	//Seteo AD_Client_ID y AD_Org_ID y lenguaje AR
	  	try {
		  	Env.setContext(Env.getCtx(), "#AD_Client_ID", additionalParams.get("CTX_Client_ID") != null ? Integer.valueOf(additionalParams.get("CTX_Client_ID")) : 1010016);
		  	Env.setContext(Env.getCtx(), "#AD_Org_ID", additionalParams.get("CTX_Org_ID") != null ? Integer.valueOf(additionalParams.get("CTX_Org_ID")) : 0);
		  	Env.setContext(Env.getCtx(), "#AD_Language", "es_AR");
		  	
		  	System.out.println("AD_Client_ID: " + Env.getAD_Client_ID(Env.getCtx()));
		  	System.out.println("AD_Org_ID: " + Env.getAD_Org_ID(Env.getCtx()));
		  	
	  	} catch (Exception e) {
	  		System.err.println("ERROR: Error al setear datos de contexto, verifique los parámetros CTX_");
	  		System.exit(1);
	  	}
	  	  	
	  	/**********************  INVOCACION DEL PROCESO  ********************************/
	  	
	  	/* Iniciar la transacción  */
		String local_trxName = Trx.createTrxName();
		Trx.getTrx(local_trxName).start();
		
		try {
			String sql = "SELECT ad_process_id FROM ad_process WHERE value = ? ";
			int processId = DB.getSQLValue(local_trxName, sql, processName);
			MProcess process = new MProcess(Env.getCtx(), processId, local_trxName);
			ProcessInfo pi = new ProcessInfo("Ejecutando Proceso Custom", process.getAD_Process_ID());
			
			//Parámetros del proceso 
			PreparedStatement pstmt = ProcessParameter.GetProcessParameters(processId);
        	ResultSet rs = pstmt.executeQuery();
        	System.out.println("Parámetros del proceso: ");
        	while (rs.next()) {
        		// Recuperar parametro pasado como argumento desde la terminal 
        		String paramName = rs.getString("ColumnName");
        		Object paramValue = createParamValue(additionalParams.get(paramName), rs.getInt("AD_Reference_ID"));
        		
        		System.out.println(paramName + ": " + paramValue);
        		
                if (paramValue == null)
                	continue;
                // TODO: parameter_To, info_To? Ver ProcessParameter.saveParameters como referencia.
        		ProcessInfoParameter aParam = new ProcessInfoParameter(paramName, paramValue, null, null, null);
        		pi.setParameter(ProcessInfoUtil.addToArray(pi.getParameter(), aParam));
        	}
			
			MProcess.execute(Env.getCtx(), process, pi);
			String result = pi.getSummary();
			
			Trx.getTrx(local_trxName).commit();
			
			System.out.println(result);
		} catch (Exception e) {
			Trx.getTrx(local_trxName).rollback();
			System.err.println("ERROR: " + e.getMessage());
		}
		
		Trx.getTrx(local_trxName).close();
	}
	
	/**
	 * Retorna el valor del parametro creado segun el tipo de dato (displayType)
	 */
	protected static Object createParamValue(String value, int displayType) {
		Object retValue = null;
		// Imposible hacer mucho mas si el value es null
		if (value == null)
			return null;
		// Instanciar segun tipo
        if  (String.class == DisplayType.getClass(displayType, false))
        	retValue = value;
        else if (Integer.class == DisplayType.getClass(displayType, false))
        	retValue = Integer.valueOf(value);
        else if (BigDecimal.class == DisplayType.getClass(displayType, false))
        	retValue = new BigDecimal(value);
        else if (Timestamp.class == DisplayType.getClass(displayType, false)) 
        	retValue = Timestamp.valueOf(value);
        else if (byte[].class == DisplayType.getClass(displayType, false))
        	retValue = value.getBytes(); 
        // Retornar valor
        return retValue;
	}

}