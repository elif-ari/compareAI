import axios from "axios";

const API_BASE = "http://localhost:8080/api/personas";

export const fetchPersonas = async () => {
  const res = await axios.get(API_BASE);
  return res.data;
};

export const fetchPersona = async (id) => {
  const res = await axios.get(`${API_BASE}/${id}`);
  return res.data;
};

export const createPersona = async (personaData) => {
  const res = await axios.post(API_BASE, personaData);
  return res.data;
};

export const updatePersona = async (id, personaData) => {
  const res = await axios.put(`${API_BASE}/${id}`, personaData);
  return res.data;
};

export const deletePersona = async (id) => {
  await axios.delete(`${API_BASE}/${id}`);
};
